package io.github.trae.database.types.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.trae.database.batch.BatchQueue;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.filter.enums.FilterOperator;
import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.index.Index;
import io.github.trae.database.index.IndexEntry;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.types.mysql.records.MySqlWriteOperation;
import lombok.Getter;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL implementation of the {@link DatabaseDriver} interface.
 *
 * <p>Uses <a href="https://github.com/brettwooldridge/HikariCP">HikariCP</a> for
 * connection pooling with tuned settings for prepared statement caching and
 * server-side prepared statements.</p>
 *
 * <p>All write operations ({@link #save}, {@link #update}, {@link #delete}) produce
 * {@link MySqlWriteOperation} instances that are collected by a {@link BatchQueue}.
 * On flush, operations are grouped by database, then executed within a single
 * transaction per group ({@code autoCommit=false} → {@code commit}).</p>
 *
 * <p>Tables and databases are created automatically on first write via
 * {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE DATABASE IF NOT EXISTS},
 * with results cached in {@link #ensuredTables} to avoid repeated DDL.</p>
 *
 * <p>The {@code _id} column is a {@code VARCHAR(36)} primary key storing the
 * UUID as a string. Column types are inferred from the Java type of the first
 * value written for each field.</p>
 *
 * @see DatabaseDriver
 * @see BatchQueue
 * @see MySqlWriteOperation
 */
public class MySqlDatabaseDriver implements DatabaseDriver {

    private static final Logger LOGGER = Logger.getLogger(MySqlDatabaseDriver.class.getName());

    private final HikariConfig hikariConfig;
    private final BatchQueue<MySqlWriteOperation> batchQueue;

    /**
     * Tracks which {@code database.table} combinations have already been
     * verified or created, avoiding redundant DDL on every save.
     */
    private final Set<String> ensuredTables = ConcurrentHashMap.newKeySet();

    @Getter
    private HikariDataSource dataSource;

    /**
     * Creates a new MySQL driver with batched write support.
     *
     * @param hikariConfig the HikariCP connection pool configuration
     * @param batchSize    the maximum number of write operations before auto-flush
     * @param period       the flush interval; {@link Duration#ZERO} for instant mode
     */
    public MySqlDatabaseDriver(final HikariConfig hikariConfig, final int batchSize, final Duration period) {
        this.hikariConfig = hikariConfig;
        this.batchQueue = new BatchQueue<>(batchSize, period, this::executeBatch);
    }

    /**
     * Opens the HikariCP connection pool using the configured settings.
     */
    @Override
    public void connect() {
        this.dataSource = new HikariDataSource(this.hikariConfig);
    }

    /**
     * Flushes all pending batched writes and closes the connection pool.
     */
    @Override
    public void disconnect() {
        this.batchQueue.shutdown();

        if (this.dataSource != null) {
            this.dataSource.close();
        }
    }

    /**
     * Queues an upsert operation using {@code INSERT ... ON DUPLICATE KEY UPDATE}.
     *
     * <p>Ensures the target database and table exist before queuing. The upsert
     * resolves conflicts via the primary key ({@code _id}) or any unique index
     * on the target table. All properties are written as column values, with
     * duplicates updated to the new values.</p>
     *
     * <p>Note: the {@code filterList} parameter is not used by the MySQL
     * implementation — conflict resolution is handled by the database's
     * unique constraints. It is accepted for interface compatibility.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList unused in MySQL; conflict resolution uses unique constraints
     * @param dataMap    the property name to value map
     */
    @Override
    public void save(final String database, final String collection, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        this.ensureTable(database, collection, dataMap);

        final List<String> columns = new ArrayList<>();
        final List<Object> values = new ArrayList<>();

        columns.add("_id");
        values.add(identifier.toString());

        for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
            columns.add(entry.getKey());
            values.add(entry.getValue());
        }

        final String columnList = String.join(", ", columns.stream().map("`%s`"::formatted).toList());
        final String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        final String onDuplicate = String.join(", ", dataMap.keySet().stream().map(c -> "`%s` = VALUES(`%s`)".formatted(c, c)).toList());
        final String sql = "INSERT INTO `%s`.`%s` (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s".formatted(database, collection, columnList, placeholders, onDuplicate);

        this.batchQueue.add(new MySqlWriteOperation(database, sql, values));
    }

    /**
     * Queues an {@code UPDATE ... SET} operation for the specified fields only.
     *
     * <p>If a filter list is provided, the update matches on those fields
     * instead of {@code _id}.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap    the property name to value map of fields to update
     */
    @Override
    public void update(final String database, final String collection, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        final List<Object> values = new ArrayList<>(dataMap.values());
        final String setClause = String.join(", ", dataMap.keySet().stream().map("`%s` = ?"::formatted).toList());

        if (filterList != null && !filterList.isEmpty()) {
            final String where = this.buildWhereClause(filterList, values);
            final String sql = "UPDATE `%s`.`%s` SET %s %s".formatted(database, collection, setClause, where);

            this.batchQueue.add(new MySqlWriteOperation(database, sql, values));
        } else {
            values.add(identifier.toString());

            final String sql = "UPDATE `%s`.`%s` SET %s WHERE `_id` = ?".formatted(database, collection, setClause);

            this.batchQueue.add(new MySqlWriteOperation(database, sql, values));
        }
    }

    /**
     * Queues a {@code DELETE FROM} operation.
     *
     * <p>If a filter list is provided, the delete matches on those fields
     * instead of {@code _id}.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList the filter conditions for matching, or empty/null to match on {@code _id}
     */
    @Override
    public void delete(final String database, final String collection, final UUID identifier, final List<Filter> filterList) {
        if (filterList != null && !filterList.isEmpty()) {
            final List<Object> values = new ArrayList<>();
            final String where = this.buildWhereClause(filterList, values);
            final String sql = "DELETE FROM `%s`.`%s` %s".formatted(database, collection, where);

            this.batchQueue.add(new MySqlWriteOperation(database, sql, values));
        } else {
            final String sql = "DELETE FROM `%s`.`%s` WHERE `_id` = ?".formatted(database, collection);

            this.batchQueue.add(new MySqlWriteOperation(database, sql, List.of(identifier.toString())));
        }
    }

    /**
     * Executes a batch of write operations within grouped transactions.
     *
     * <p>Operations are grouped by database name. Each group executes within a
     * single transaction — {@code autoCommit} is disabled, all statements execute,
     * then the connection is committed.</p>
     *
     * @param operations the batch of write operations to execute
     */
    private void executeBatch(final List<MySqlWriteOperation> operations) {
        final LinkedHashMap<String, List<MySqlWriteOperation>> grouped = new LinkedHashMap<>();

        for (final MySqlWriteOperation operation : operations) {
            grouped.computeIfAbsent(operation.databaseName(), k -> new ArrayList<>()).add(operation);
        }

        for (final Map.Entry<String, List<MySqlWriteOperation>> entry : grouped.entrySet()) {
            try (final Connection connection = this.dataSource.getConnection()) {
                connection.setAutoCommit(false);

                for (final MySqlWriteOperation operation : entry.getValue()) {
                    try (final PreparedStatement statement = connection.prepareStatement(operation.sql())) {
                        for (int i = 0; i < operation.values().size(); i++) {
                            statement.setObject(i + 1, operation.values().get(i));
                        }
                        statement.executeUpdate();
                    }
                }

                connection.commit();
            } catch (final SQLException e) {
                LOGGER.log(Level.SEVERE, "MySQL batch execution failed", e);
            }
        }
    }

    /**
     * Synchronously finds a single row by its {@code _id}.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the UUID to look up
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final UUID identifier) {
        final String sql = "SELECT * FROM `%s`.`%s` WHERE `_id` = ? LIMIT 1".formatted(database, collection);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, identifier.toString());

            try (final ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(this.resultSetToMap(resultSet));
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findOneSynchronously failed", e);
        }

        return Optional.empty();
    }

    /**
     * Synchronously finds a single row matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param filters    the filter conditions to apply
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final List<Filter> filters) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT * FROM `%s`.`%s` %s LIMIT 1".formatted(database, collection, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(this.resultSetToMap(resultSet));
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findOneSynchronously failed", e);
        }

        return Optional.empty();
    }

    /**
     * Synchronously finds a single row matching the given query options.
     *
     * <p>Applies {@code ORDER BY}, {@code LIMIT 1}, and {@code OFFSET} from the
     * {@link QueryOptions} to the generated SQL.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param options    the query options including filters, sort, and skip
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final QueryOptions options) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(options.getFilters(), values);
        final String orderBy = options.getField() != null ? " ORDER BY `%s` %s".formatted(options.getField(), this.toSqlDirection(options.getSortDirection())) : "";
        final String offset = options.getSkip() > 0 ? " OFFSET %d".formatted(options.getSkip()) : "";
        final String sql = "SELECT * FROM `%s`.`%s` %s%s LIMIT 1%s".formatted(database, collection, where, orderBy, offset);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(this.resultSetToMap(resultSet));
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findOneSynchronously failed", e);
        }

        return Optional.empty();
    }

    /**
     * Synchronously finds all rows matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param filters    the filter conditions to apply
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String database, final String collection, final List<Filter> filters) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT * FROM `%s`.`%s` %s".formatted(database, collection, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(this.resultSetToMap(resultSet));
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findManySynchronously failed", e);
        }

        return results;
    }

    /**
     * Synchronously finds all rows matching the given query options.
     *
     * <p>Applies {@code ORDER BY}, {@code LIMIT}, and {@code OFFSET} from the
     * {@link QueryOptions} to the generated SQL.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param options    the query options including filters, sort, limit, and skip
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String database, final String collection, final QueryOptions options) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(options.getFilters(), values);
        final String orderBy = options.getField() != null ? " ORDER BY `%s` %s".formatted(options.getField(), this.toSqlDirection(options.getSortDirection())) : "";
        final String limit = options.getLimit() > 0 ? " LIMIT %d".formatted(options.getLimit()) : "";
        final String offset = options.getSkip() > 0 ? " OFFSET %d".formatted(options.getSkip()) : "";
        final String sql = "SELECT * FROM `%s`.`%s` %s%s%s%s".formatted(database, collection, where, orderBy, limit, offset);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(this.resultSetToMap(resultSet));
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findManySynchronously failed", e);
        }

        return results;
    }

    /**
     * Asynchronously finds a single row by its {@code _id}.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the UUID to look up
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final UUID identifier) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, identifier));
    }

    /**
     * Asynchronously finds a single row matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param filters    the filter conditions to apply
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, filters));
    }

    /**
     * Asynchronously finds a single row matching the given query options.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param options    the query options including filters, sort, and skip
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, options));
    }

    /**
     * Asynchronously finds all rows matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param filters    the filter conditions to apply
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String database, final String collection, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(database, collection, filters));
    }

    /**
     * Asynchronously finds all rows matching the given query options.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param options    the query options including filters, sort, limit, and skip
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String database, final String collection, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(database, collection, options));
    }

    /**
     * Checks row existence using {@code SELECT 1 ... LIMIT 1} for maximum efficiency.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param identifier the UUID to check
     * @return {@code true} if the row exists
     */
    @Override
    public boolean exists(final String database, final String collection, final UUID identifier) {
        final String sql = "SELECT 1 FROM `%s`.`%s` WHERE `_id` = ? LIMIT 1".formatted(database, collection);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setObject(1, identifier.toString());

            try (final ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL exists check failed", e);
        }

        return false;
    }

    /**
     * Returns the total number of rows in the table.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @return the row count
     */
    @Override
    public long count(final String database, final String collection) {
        final String sql = "SELECT COUNT(*) FROM `%s`.`%s`".formatted(database, collection);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            try (final ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL count failed", e);
        }

        return 0L;
    }

    /**
     * Returns the number of rows matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param filters    the filter conditions to apply
     * @return the matching row count
     */
    @Override
    public long count(final String database, final String collection, final List<Filter> filters) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT COUNT(*) FROM `%s`.`%s` %s".formatted(database, collection, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL count failed", e);
        }

        return 0L;
    }

    /**
     * Creates an index on the target table.
     *
     * <p>Generates a {@code CREATE [UNIQUE] INDEX} statement with an auto-generated
     * index name based on the table and field names. Duplicate index errors are
     * silently ignored.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param index      the index definition
     */
    @Override
    public void createIndex(final String database, final String collection, final Index index) {
        final List<String> columns = new ArrayList<>();

        for (final IndexEntry entry : index.getEntries()) {
            columns.add("`%s` %s".formatted(entry.getField(), this.toSqlDirection(entry.getDirection())));
        }

        final String indexName = "idx_%s_%s".formatted(collection, String.join("_", index.getEntries().stream().map(IndexEntry::getField).toList()));
        final String unique = index.isUnique() ? "UNIQUE " : "";
        final String sql = "CREATE %sINDEX `%s` ON `%s`.`%s` (%s)".formatted(unique, indexName, database, collection, String.join(", ", columns));

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();
        } catch (final SQLException e) {
            if (!(e.getMessage().contains("Duplicate"))) {
                LOGGER.log(Level.SEVERE, "MySQL createIndex failed", e);
            }
        }
    }

    /**
     * Ensures the target database and table exist, creating them if necessary.
     *
     * <p>Results are cached in {@link #ensuredTables} so DDL only executes
     * once per {@code database.collection} combination for the lifetime
     * of this driver instance.</p>
     *
     * @param database   the target database name
     * @param collection the target table name
     * @param dataMap    the data map used to infer column types
     */
    private void ensureTable(final String database, final String collection, final LinkedHashMap<String, Object> dataMap) {
        final String key = "%s.%s".formatted(database, collection);

        if (this.ensuredTables.contains(key)) {
            return;
        }

        this.ensureDatabase(database);

        final List<String> columns = new ArrayList<>();
        columns.add("`_id` VARCHAR(36) NOT NULL PRIMARY KEY");

        for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
            columns.add("`%s` %s".formatted(entry.getKey(), this.toSqlType(entry.getValue())));
        }

        final String sql = "CREATE TABLE IF NOT EXISTS `%s`.`%s` (%s)".formatted(database, collection, String.join(", ", columns));

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();

            this.ensuredTables.add(key);
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL ensureTable failed", e);
        }
    }

    /**
     * Creates the target database if it does not exist.
     *
     * @param database the database name to ensure
     */
    private void ensureDatabase(final String database) {
        final String sql = "CREATE DATABASE IF NOT EXISTS `%s`".formatted(database);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.executeUpdate();
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL ensureDatabase failed", e);
        }
    }

    /**
     * Converts a {@link ResultSet} row into a {@link LinkedHashMap}.
     *
     * @param resultSet the result set positioned on a valid row
     * @return a map of column names to their values
     * @throws SQLException if a database access error occurs
     */
    private LinkedHashMap<String, Object> resultSetToMap(final ResultSet resultSet) throws SQLException {
        final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        final ResultSetMetaData metaData = resultSet.getMetaData();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            map.put(metaData.getColumnName(i), resultSet.getObject(i));
        }

        return map;
    }

    /**
     * Builds a SQL {@code WHERE} clause from a list of filters.
     *
     * <p>Each filter is translated to a parameterized condition, and its value
     * is appended to the {@code values} list for prepared statement binding.
     * Returns an empty string if the filter list is null or empty.</p>
     *
     * @param filters the filter conditions
     * @param values  the mutable list to append parameter values to
     * @return the {@code WHERE ...} clause, or an empty string
     */
    private String buildWhereClause(final List<Filter> filters, final List<Object> values) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }

        final List<String> conditions = new ArrayList<>();

        for (final Filter filter : filters) {
            conditions.add(this.toSqlCondition(filter, values));
        }

        return "WHERE %s".formatted(String.join(" AND ", conditions));
    }

    /**
     * Translates a single {@link Filter} into a parameterized SQL condition.
     *
     * <p>Appends the filter's value(s) to the provided list for prepared
     * statement binding. Special cases:</p>
     * <ul>
     *     <li>{@link FilterOperator#IN} / {@link FilterOperator#NOT_IN} — expands to multiple placeholders</li>
     *     <li>{@link FilterOperator#EXISTS} — maps to {@code IS [NOT] NULL}</li>
     *     <li>{@link FilterOperator#REGEX} — maps to {@code REGEXP}</li>
     * </ul>
     *
     * @param filter the filter to translate
     * @param values the mutable list to append parameter values to
     * @return the SQL condition string
     */
    private String toSqlCondition(final Filter filter, final List<Object> values) {
        final String field = "`%s`".formatted(filter.getField());
        final FilterOperator operator = filter.getOperator();

        return switch (operator) {
            case EQUALS -> {
                values.add(filter.getValue());
                yield "%s = ?".formatted(field);
            }
            case NOT_EQUALS -> {
                values.add(filter.getValue());
                yield "%s != ?".formatted(field);
            }
            case GREATER_THAN -> {
                values.add(filter.getValue());
                yield "%s > ?".formatted(field);
            }
            case GREATER_THAN_OR_EQUALS -> {
                values.add(filter.getValue());
                yield "%s >= ?".formatted(field);
            }
            case LESS_THAN -> {
                values.add(filter.getValue());
                yield "%s < ?".formatted(field);
            }
            case LESS_THAN_OR_EQUALS -> {
                values.add(filter.getValue());
                yield "%s <= ?".formatted(field);
            }
            case IN -> {
                final List<?> inValues = (List<?>) filter.getValue();
                final String placeholders = String.join(", ", inValues.stream().map(v -> "?").toList());
                values.addAll(inValues);
                yield "%s IN (%s)".formatted(field, placeholders);
            }
            case NOT_IN -> {
                final List<?> notInValues = (List<?>) filter.getValue();
                final String placeholders = String.join(", ", notInValues.stream().map(v -> "?").toList());
                values.addAll(notInValues);
                yield "%s NOT IN (%s)".formatted(field, placeholders);
            }
            case EXISTS -> {
                final boolean exists = (Boolean) filter.getValue();
                yield exists ? "%s IS NOT NULL".formatted(field) : "%s IS NULL".formatted(field);
            }
            case REGEX -> {
                values.add(filter.getValue());
                yield "%s REGEXP ?".formatted(field);
            }
        };
    }

    /**
     * Maps a Java type to the corresponding MySQL column type.
     *
     * <p>Used during automatic table creation to infer column types from
     * the first data map written to a table.</p>
     *
     * @param value the Java value to map
     * @return the MySQL column type string
     */
    private String toSqlType(final Object value) {
        if (value == null) {
            return "TEXT";
        }

        if (value instanceof String) {
            return "TEXT";
        }

        if (value instanceof Integer) {
            return "INT";
        }

        if (value instanceof Long) {
            return "BIGINT";
        }

        if (value instanceof Double || value instanceof Float) {
            return "DOUBLE";
        }

        if (value instanceof Boolean) {
            return "TINYINT(1)";
        }

        return "TEXT";
    }

    /**
     * Converts a {@link SortDirection} to its SQL keyword.
     *
     * @param direction the sort direction
     * @return {@code "ASC"} or {@code "DESC"}
     */
    private String toSqlDirection(final SortDirection direction) {
        return switch (direction) {
            case ASCENDING -> "ASC";
            case DESCENDING -> "DESC";
        };
    }
}