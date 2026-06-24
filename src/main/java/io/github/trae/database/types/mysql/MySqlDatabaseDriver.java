package io.github.trae.database.types.mysql;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.trae.database.batch.BatchQueue;
import io.github.trae.database.constants.Constants;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.filter.enums.FilterOperator;
import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.index.Index;
import io.github.trae.database.index.IndexEntry;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.types.mysql.records.MySqlWriteOperation;
import lombok.Getter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * transaction per group ({@code autoCommit=false} → {@code commit}). Within a
 * transaction, consecutive operations sharing identical SQL are executed as a
 * single JDBC batch via {@link PreparedStatement#addBatch()}.</p>
 *
 * <p>Tables and databases are created automatically on first write via
 * {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE DATABASE IF NOT EXISTS},
 * with results cached in {@link #ensuredTables} to avoid repeated DDL.</p>
 *
 * <p>The {@code _id} column is a {@code VARCHAR(36)} primary key storing the
 * UUID as a string. Column types are inferred from the Java type of the first
 * value written for each field. {@link Map} and {@link List} values are stored as
 * {@code JSON} columns; on read they are deserialized via {@link Gson} and then
 * recursively normalized by {@link #normalizeValue} so every nested object is a
 * {@link LinkedHashMap} — the shape
 * {@link io.github.trae.database.domain.data.DomainData} expects during
 * deserialization. The same JSON handling applies to projected property reads.</p>
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
     * <p>Ensures the target database and table exist before queuing. Conflict
     * resolution is performed by the database against the primary key ({@code _id})
     * or any {@code UNIQUE} index on the target table — on a collision the matched
     * row's columns are updated to the new values, otherwise a new row is inserted.</p>
     *
     * <p>The {@code filterList} is used to <b>seed the inserted row's columns</b> for
     * compound-key upserts. Each {@link FilterOperator#EQUALS} filter contributes its
     * field/value to the {@code INSERT} (unless that field is already present in
     * {@code dataMap}), ensuring that the columns backing the unique index are
     * populated on a first insert. Non-{@code EQUALS} filters are ignored, as they
     * cannot supply a concrete starting value.</p>
     *
     * <p><b>Requirement:</b> for the upsert to actually match on the filter fields
     * (rather than only on {@code _id}), the target table must have a {@code UNIQUE}
     * index over those fields — e.g. {@code UNIQUE(serverId, username)}. Without it,
     * MySQL has no constraint to collide on and every {@code save} inserts a new row.
     * The auto-created table only declares {@code _id} as its primary key, so any
     * required compound-unique index must be created separately via
     * {@link #createIndex}.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the domain's UUID ({@code _id})
     * @param filterList     the compound-key fields to seed on insert ({@code EQUALS} filters only), or empty/null for an {@code _id}-only upsert
     * @param dataMap        the property name to value map
     */
    @Override
    public void save(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        this.ensureTable(databaseName, collectionName, dataMap);

        final List<String> columns = new ArrayList<>();
        final List<Object> values = new ArrayList<>();

        columns.add("_id");
        values.add(identifier.toString());

        if (filterList != null) {
            for (final Filter filter : filterList) {
                if (filter.getOperator() == FilterOperator.EQUALS && !dataMap.containsKey(filter.getField()) && !columns.contains(filter.getField())) {
                    columns.add(filter.getField());
                    values.add(this.toSqlValue(filter.getValue()));
                }
            }
        }

        for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
            columns.add(entry.getKey());
            values.add(this.toSqlValue(entry.getValue()));
        }

        final String columnList = String.join(", ", columns.stream().map("`%s`"::formatted).toList());
        final String placeholders = String.join(", ", columns.stream().map(c -> "?").toList());
        final String onDuplicate = String.join(", ", dataMap.keySet().stream().map(c -> "`%s` = VALUES(`%s`)".formatted(c, c)).toList());
        final String sql = "INSERT INTO `%s`.`%s` (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s".formatted(databaseName, collectionName, columnList, placeholders, onDuplicate);

        this.batchQueue.add(new MySqlWriteOperation(databaseName, sql, values));
    }

    /**
     * Queues an {@code UPDATE ... SET} operation for the specified fields only.
     *
     * <p>If a filter list is provided, the update matches on those fields
     * instead of {@code _id}.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the domain's UUID ({@code _id})
     * @param filterList     the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap        the property name to value map of fields to update
     */
    @Override
    public void update(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        final List<Object> values = new ArrayList<>(dataMap.values().stream().map(this::toSqlValue).toList());
        final String setClause = String.join(", ", dataMap.keySet().stream().map("`%s` = ?"::formatted).toList());

        if (filterList != null && !filterList.isEmpty()) {
            final String where = this.buildWhereClause(filterList, values);
            final String sql = "UPDATE `%s`.`%s` SET %s %s".formatted(databaseName, collectionName, setClause, where);

            this.batchQueue.add(new MySqlWriteOperation(databaseName, sql, values));
        } else {
            values.add(identifier.toString());

            final String sql = "UPDATE `%s`.`%s` SET %s WHERE `_id` = ?".formatted(databaseName, collectionName, setClause);

            this.batchQueue.add(new MySqlWriteOperation(databaseName, sql, values));
        }
    }

    /**
     * Queues a {@code DELETE FROM} operation.
     *
     * <p>If a filter list is provided, the delete matches on those fields
     * instead of {@code _id}.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the domain's UUID ({@code _id})
     * @param filterList     the filter conditions for matching, or empty/null to match on {@code _id}
     */
    @Override
    public void delete(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList) {
        if (filterList != null && !filterList.isEmpty()) {
            final List<Object> values = new ArrayList<>();
            final String where = this.buildWhereClause(filterList, values);
            final String sql = "DELETE FROM `%s`.`%s` %s".formatted(databaseName, collectionName, where);

            this.batchQueue.add(new MySqlWriteOperation(databaseName, sql, values));
        } else {
            final String sql = "DELETE FROM `%s`.`%s` WHERE `_id` = ?".formatted(databaseName, collectionName);

            this.batchQueue.add(new MySqlWriteOperation(databaseName, sql, List.of(identifier.toString())));
        }
    }

    /**
     * Executes a batch of write operations within grouped transactions.
     *
     * <p>Operations are grouped by database name. Each group executes within a
     * single transaction — {@code autoCommit} is disabled, all statements execute,
     * then the connection is committed. Consecutive operations sharing identical
     * SQL are coalesced into a single {@link PreparedStatement} using JDBC batch
     * execution ({@link PreparedStatement#addBatch()} /
     * {@link PreparedStatement#executeBatch()}), preserving operation order while
     * minimizing round trips.</p>
     *
     * <p>On failure the transaction is explicitly rolled back before the connection
     * is returned to the pool, so no partial group is committed.</p>
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

                try {
                    this.executeGroup(connection, entry.getValue());

                    connection.commit();
                } catch (final SQLException e) {
                    connection.rollback();

                    LOGGER.log(Level.SEVERE, "MySQL batch execution failed, rolled back", e);
                }
            } catch (final SQLException e) {
                LOGGER.log(Level.SEVERE, "MySQL batch connection failed", e);
            }
        }
    }

    /**
     * Executes an ordered list of operations on the given connection, coalescing
     * runs of identical SQL into a single JDBC batch.
     *
     * <p>A run is flushed whenever the next operation's SQL differs from the
     * current run's SQL, preserving the overall execution order of the group.</p>
     *
     * @param connection the active transactional connection
     * @param operations the ordered operations to execute
     * @throws SQLException if a statement fails to execute
     */
    private void executeGroup(final Connection connection, final List<MySqlWriteOperation> operations) throws SQLException {
        int index = 0;

        while (index < operations.size()) {
            final String sql = operations.get(index).sql();

            int end = index;

            while (end < operations.size() && operations.get(end).sql().equals(sql)) {
                end++;
            }

            try (final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                for (int i = index; i < end; i++) {
                    final List<Object> operationValues = operations.get(i).values();

                    for (int v = 0; v < operationValues.size(); v++) {
                        preparedStatement.setObject(v + 1, operationValues.get(v));
                    }

                    preparedStatement.addBatch();
                }

                preparedStatement.executeBatch();
            }

            index = end;
        }
    }

    /**
     * Synchronously finds a single row by its {@code _id}.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the UUID to look up
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final UUID identifier) {
        final String sql = "SELECT * FROM `%s`.`%s` WHERE `_id` = ? LIMIT 1".formatted(databaseName, collectionName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.setObject(1, identifier.toString());

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param filters        the filter conditions to apply
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final List<Filter> filters) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT * FROM `%s`.`%s` %s LIMIT 1".formatted(databaseName, collectionName, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                preparedStatement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param options        the query options including filters, sort, and skip
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final QueryOptions options) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(options.getFilters(), values);
        final String orderBy = options.getField() != null ? " ORDER BY `%s` %s".formatted(options.getField(), this.toSqlDirection(options.getSortDirection())) : "";
        final String offset = options.getSkip() > 0 ? " OFFSET %d".formatted(options.getSkip()) : "";
        final String sql = "SELECT * FROM `%s`.`%s` %s%s LIMIT 1%s".formatted(databaseName, collectionName, where, orderBy, offset);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                preparedStatement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param filters        the filter conditions to apply
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String databaseName, final String collectionName, final List<Filter> filters) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT * FROM `%s`.`%s` %s".formatted(databaseName, collectionName, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                preparedStatement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param options        the query options including filters, sort, limit, and skip
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String databaseName, final String collectionName, final QueryOptions options) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(options.getFilters(), values);
        final String orderBy = options.getField() != null ? " ORDER BY `%s` %s".formatted(options.getField(), this.toSqlDirection(options.getSortDirection())) : "";
        final String limit = options.getLimit() > 0 ? " LIMIT %d".formatted(options.getLimit()) : "";
        final String offset = options.getSkip() > 0 ? " OFFSET %d".formatted(options.getSkip()) : "";
        final String sql = "SELECT * FROM `%s`.`%s` %s%s%s%s".formatted(databaseName, collectionName, where, orderBy, limit, offset);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                preparedStatement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the UUID to look up
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final UUID identifier) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(databaseName, collectionName, identifier));
    }

    /**
     * Asynchronously finds a single row matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param filters        the filter conditions to apply
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(databaseName, collectionName, filters));
    }

    /**
     * Asynchronously finds a single row matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param options        the query options including filters, sort, and skip
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(databaseName, collectionName, options));
    }

    /**
     * Asynchronously finds all rows matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param filters        the filter conditions to apply
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String databaseName, final String collectionName, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(databaseName, collectionName, filters));
    }

    /**
     * Asynchronously finds all rows matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param options        the query options including filters, sort, limit, and skip
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String databaseName, final String collectionName, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(databaseName, collectionName, options));
    }

    /**
     * Synchronously reads a single projected property from one row by its {@code _id}.
     *
     * <p>Selects only the named column. If that column is of SQL type {@code JSON},
     * the stored string is deserialized via {@link Gson} and normalized by
     * {@link #normalizeValue}; otherwise the raw column value is returned.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the UUID to look up
     * @param propertyKey    the column to select and return
     * @return an {@link Optional} containing the column value, or empty if the row is absent or the value is {@code null}
     */
    @Override
    public Optional<Object> findOneByPropertySynchronously(final String databaseName, final String collectionName, final UUID identifier, final String propertyKey) {
        final String sql = """
                SELECT `%s`
                FROM `%s`.`%s`
                WHERE `_id` = ?
                LIMIT 1
                """.formatted(propertyKey, databaseName, collectionName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.setString(1, identifier.toString());

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    Object value = resultSet.getObject(propertyKey);

                    final ResultSetMetaData metaData = resultSet.getMetaData();
                    final int columnIndex = resultSet.findColumn(propertyKey);

                    if ("JSON".equalsIgnoreCase(metaData.getColumnTypeName(columnIndex)) && value instanceof final String jsonString) {
                        value = this.normalizeValue(Constants.GSON.fromJson(jsonString, Object.class));
                    }

                    return value == null ? Optional.empty() : Optional.of(value);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findOneByPropertySynchronously failed", e);
        }

        return Optional.empty();
    }

    /**
     * Asynchronously reads a single projected property from one row by its {@code _id}.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the UUID to look up
     * @param propertyKey    the column to select and return
     * @return a future resolving to an {@link Optional} containing the column value
     */
    @Override
    public CompletableFuture<Optional<Object>> findOneByPropertyAsynchronously(final String databaseName, final String collectionName, final UUID identifier, final String propertyKey) {
        return CompletableFuture.supplyAsync(() -> this.findOneByPropertySynchronously(databaseName, collectionName, identifier, propertyKey));
    }

    /**
     * Synchronously reads several projected properties from one row by its {@code _id}.
     *
     * <p>Selects only the named columns. The returned map preserves the requested key
     * order. {@code JSON} columns are deserialized via {@link Gson} and normalized by
     * {@link #normalizeValue}; other columns are returned as-is.</p>
     *
     * @param databaseName    the target database name
     * @param collectionName  the target table name
     * @param identifier      the UUID to look up
     * @param propertyKeyList the columns to select and return
     * @return an {@link Optional} containing a column-to-value map, or empty if the row is absent
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneByManyPropertySynchronously(final String databaseName, final String collectionName, final UUID identifier, final List<String> propertyKeyList) {
        final String columns = String.join(", ",
                propertyKeyList.stream()
                        .map("`%s`"::formatted)
                        .toList()
        );

        final String sql = """
                SELECT %s
                FROM `%s`.`%s`
                WHERE `_id` = ?
                LIMIT 1
                """.formatted(columns, databaseName, collectionName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.setString(1, identifier.toString());

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
                    final ResultSetMetaData metaData = resultSet.getMetaData();

                    for (final String propertyKey : propertyKeyList) {
                        Object value = resultSet.getObject(propertyKey);

                        final int columnIndex = resultSet.findColumn(propertyKey);

                        if ("JSON".equalsIgnoreCase(metaData.getColumnTypeName(columnIndex)) && value instanceof final String jsonString) {
                            value = this.normalizeValue(Constants.GSON.fromJson(jsonString, Object.class));
                        }

                        map.put(propertyKey, value);
                    }

                    return Optional.of(map);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findOneByManyPropertySynchronously failed", e);
        }

        return Optional.empty();
    }

    /**
     * Asynchronously reads several projected properties from one row by its {@code _id}.
     *
     * @param databaseName    the target database name
     * @param collectionName  the target table name
     * @param identifier      the UUID to look up
     * @param propertyKeyList the columns to select and return
     * @return a future resolving to an {@link Optional} containing a column-to-value map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneByManyPropertyAsynchronously(final String databaseName, final String collectionName, final UUID identifier, final List<String> propertyKeyList) {
        return CompletableFuture.supplyAsync(() -> this.findOneByManyPropertySynchronously(databaseName, collectionName, identifier, propertyKeyList));
    }

    /**
     * Synchronously reads a single projected property from many rows in one query.
     *
     * <p>Matches all identifiers via a parameterized {@code _id IN (...)} clause and
     * selects the single named column, returning a map from each found {@code _id} to
     * its value. If the column is of SQL type {@code JSON} its values are deserialized
     * via {@link Gson} and normalized by {@link #normalizeValue}; the JSON-ness of the
     * column is resolved once from the result metadata rather than per row. An empty
     * identifier list short-circuits to an empty map, since {@code IN ()} is invalid SQL.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifierList the identifiers to resolve
     * @param propertyKey    the column to select and return for each
     * @return a map from identifier to its column value, empty if none match or the input list is empty
     */
    @Override
    public LinkedHashMap<UUID, Object> findManyByOnePropertySynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final String propertyKey) {
        final LinkedHashMap<UUID, Object> results = new LinkedHashMap<>();

        if (identifierList.isEmpty()) {
            return results;
        }

        final String placeholders = String.join(", ", identifierList.stream().map(id -> "?").toList());
        final String sql = """
                SELECT `_id`, `%s`
                FROM `%s`.`%s`
                WHERE `_id` IN (%s)
                """.formatted(propertyKey, databaseName, collectionName, placeholders);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < identifierList.size(); i++) {
                preparedStatement.setString(i + 1, identifierList.get(i).toString());
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                final ResultSetMetaData metaData = resultSet.getMetaData();
                final int propertyColumnIndex = resultSet.findColumn(propertyKey);
                final boolean isJson = "JSON".equalsIgnoreCase(metaData.getColumnTypeName(propertyColumnIndex));

                while (resultSet.next()) {
                    Object value = resultSet.getObject(propertyKey);

                    if (isJson && value instanceof final String jsonString) {
                        value = this.normalizeValue(Constants.GSON.fromJson(jsonString, Object.class));
                    }

                    results.put(UUID.fromString(resultSet.getString("_id")), value);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findManyByOnePropertySynchronously failed", e);
        }

        return results;
    }

    /**
     * Asynchronously reads a single projected property from many rows in one query.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifierList the identifiers to resolve
     * @param propertyKey    the column to select and return for each
     * @return a future resolving to a map from identifier to its column value
     */
    @Override
    public CompletableFuture<LinkedHashMap<UUID, Object>> findManyByOnePropertyAsynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final String propertyKey) {
        return CompletableFuture.supplyAsync(() -> this.findManyByOnePropertySynchronously(databaseName, collectionName, identifierList, propertyKey));
    }

    /**
     * Synchronously reads several projected properties from many rows in one query.
     *
     * <p>Matches all identifiers via a parameterized {@code _id IN (...)} clause and
     * selects the named columns, returning a map from each found {@code _id} to a
     * column-to-value map. Each inner map preserves the requested key order. {@code JSON}
     * columns are deserialized via {@link Gson} and normalized by {@link #normalizeValue};
     * other columns are returned as-is. An empty identifier list short-circuits to an
     * empty map, since {@code IN ()} is invalid SQL.</p>
     *
     * @param databaseName    the target database name
     * @param collectionName  the target table name
     * @param identifierList  the identifiers to resolve
     * @param propertyKeyList the columns to select and return for each
     * @return a map from identifier to its column-to-value map, empty if none match or the input list is empty
     */
    @Override
    public LinkedHashMap<UUID, LinkedHashMap<String, Object>> findManyByManyPropertySynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final List<String> propertyKeyList) {
        final LinkedHashMap<UUID, LinkedHashMap<String, Object>> results = new LinkedHashMap<>();

        if (identifierList.isEmpty()) {
            return results;
        }

        final String columns = String.join(", ", propertyKeyList.stream().map("`%s`"::formatted).toList());
        final String placeholders = String.join(", ", identifierList.stream().map(id -> "?").toList());
        final String sql = """
                SELECT `_id`, %s
                FROM `%s`.`%s`
                WHERE `_id` IN (%s)
                """.formatted(columns, databaseName, collectionName, placeholders);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < identifierList.size(); i++) {
                preparedStatement.setString(i + 1, identifierList.get(i).toString());
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
                final ResultSetMetaData metaData = resultSet.getMetaData();

                while (resultSet.next()) {
                    final LinkedHashMap<String, Object> map = new LinkedHashMap<>();

                    for (final String propertyKey : propertyKeyList) {
                        Object value = resultSet.getObject(propertyKey);

                        final int columnIndex = resultSet.findColumn(propertyKey);

                        if ("JSON".equalsIgnoreCase(metaData.getColumnTypeName(columnIndex)) && value instanceof final String jsonString) {
                            value = this.normalizeValue(Constants.GSON.fromJson(jsonString, Object.class));
                        }

                        map.put(propertyKey, value);
                    }

                    results.put(UUID.fromString(resultSet.getString("_id")), map);
                }
            }
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL findManyByManyPropertySynchronously failed", e);
        }

        return results;
    }

    /**
     * Asynchronously reads several projected properties from many rows in one query.
     *
     * @param databaseName    the target database name
     * @param collectionName  the target table name
     * @param identifierList  the identifiers to resolve
     * @param propertyKeyList the columns to select and return for each
     * @return a future resolving to a map from identifier to its column-to-value map
     */
    @Override
    public CompletableFuture<LinkedHashMap<UUID, LinkedHashMap<String, Object>>> findManyByManyPropertyAsynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final List<String> propertyKeyList) {
        return CompletableFuture.supplyAsync(() -> this.findManyByManyPropertySynchronously(databaseName, collectionName, identifierList, propertyKeyList));
    }

    /**
     * Checks row existence using {@code SELECT 1 ... LIMIT 1} for maximum efficiency.
     *
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param identifier     the UUID to check
     * @return {@code true} if the row exists
     */
    @Override
    public boolean exists(final String databaseName, final String collectionName, final UUID identifier) {
        final String sql = "SELECT 1 FROM `%s`.`%s` WHERE `_id` = ? LIMIT 1".formatted(databaseName, collectionName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.setObject(1, identifier.toString());

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @return the row count
     */
    @Override
    public long count(final String databaseName, final String collectionName) {
        final String sql = "SELECT COUNT(*) FROM `%s`.`%s`".formatted(databaseName, collectionName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param filters        the filter conditions to apply
     * @return the matching row count
     */
    @Override
    public long count(final String databaseName, final String collectionName, final List<Filter> filters) {
        final List<Object> values = new ArrayList<>();
        final String where = this.buildWhereClause(filters, values);
        final String sql = "SELECT COUNT(*) FROM `%s`.`%s` %s".formatted(databaseName, collectionName, where);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            for (int i = 0; i < values.size(); i++) {
                preparedStatement.setObject(i + 1, values.get(i));
            }

            try (final ResultSet resultSet = preparedStatement.executeQuery()) {
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param index          the index definition
     */
    @Override
    public void createIndex(final String databaseName, final String collectionName, final Index index) {
        final List<String> columns = new ArrayList<>();

        for (final IndexEntry entry : index.getEntries()) {
            columns.add("`%s` %s".formatted(entry.getField(), this.toSqlDirection(entry.getDirection())));
        }

        final String indexName = "idx_%s_%s".formatted(collectionName, String.join("_", index.getEntries().stream().map(IndexEntry::getField).toList()));
        final String unique = index.isUnique() ? "UNIQUE " : "";
        final String sql = "CREATE %sINDEX `%s` ON `%s`.`%s` (%s)".formatted(unique, indexName, databaseName, collectionName, String.join(", ", columns));

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.executeUpdate();
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
     * @param databaseName   the target database name
     * @param collectionName the target table name
     * @param dataMap        the data map used to infer column types
     */
    private void ensureTable(final String databaseName, final String collectionName, final LinkedHashMap<String, Object> dataMap) {
        final String key = "%s.%s".formatted(databaseName, collectionName);

        if (this.ensuredTables.contains(key)) {
            return;
        }

        this.ensureDatabase(databaseName);

        final List<String> columns = new ArrayList<>();
        columns.add("`_id` VARCHAR(36) NOT NULL PRIMARY KEY");

        for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
            columns.add("`%s` %s".formatted(entry.getKey(), this.toSqlType(entry.getValue())));
        }

        final String sql = "CREATE TABLE IF NOT EXISTS `%s`.`%s` (%s)".formatted(databaseName, collectionName, String.join(", ", columns));

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.executeUpdate();

            this.ensuredTables.add(key);
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL ensureTable failed", e);
        }
    }

    /**
     * Creates the target database if it does not exist.
     *
     * @param databaseName the database name to ensure
     */
    private void ensureDatabase(final String databaseName) {
        final String sql = "CREATE DATABASE IF NOT EXISTS `%s`".formatted(databaseName);

        try (final Connection connection = this.dataSource.getConnection();
             final PreparedStatement preparedStatement = connection.prepareStatement(sql)) {

            preparedStatement.executeUpdate();
        } catch (final SQLException e) {
            LOGGER.log(Level.SEVERE, "MySQL ensureDatabase failed", e);
        }
    }

    /**
     * Converts a {@link ResultSet} row into a {@link LinkedHashMap}.
     *
     * <p>{@code JSON} columns are automatically deserialized via {@link Gson}
     * using {@code Object.class} to support both top-level maps and arrays,
     * then recursively normalized to ensure all nested maps and list elements
     * are returned as {@link LinkedHashMap} instances, which is required by
     * {@link io.github.trae.database.domain.data.DomainData} for type
     * matching during deserialization.</p>
     *
     * @param resultSet the result set positioned on a valid row
     * @return a map of column names to their values
     * @throws SQLException if a database access error occurs
     */
    private LinkedHashMap<String, Object> resultSetToMap(final ResultSet resultSet) throws SQLException {
        final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        final ResultSetMetaData metaData = resultSet.getMetaData();

        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            Object value = resultSet.getObject(i);

            if (metaData.getColumnTypeName(i).equalsIgnoreCase("JSON") && value instanceof final String jsonString) {
                value = this.normalizeValue(Constants.GSON.fromJson(jsonString, Object.class));
            }

            map.put(metaData.getColumnName(i), value);
        }

        return map;
    }

    /**
     * Recursively normalizes deserialized JSON values to ensure all nested
     * maps are {@link LinkedHashMap} instances and all list elements
     * containing maps are also normalized.
     *
     * <p>Gson deserializes JSON objects as {@code LinkedTreeMap} and arrays
     * as {@code ArrayList}, which do not match the {@link LinkedHashMap}
     * type expected by {@link io.github.trae.database.domain.data.DomainData}.
     * This method walks the entire structure and converts every map to
     * {@link LinkedHashMap} at every depth.</p>
     *
     * @param value the value to normalize
     * @return the normalized value
     */
    private Object normalizeValue(final Object value) {
        if (value instanceof final Map<?, ?> map) {
            final LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), this.normalizeValue(entry.getValue()));
            }
            return normalized;
        }

        if (value instanceof final List<?> list) {
            return list.stream().map(this::normalizeValue).toList();
        }

        return value;
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
     * Converts a Java value to its SQL-compatible representation for prepared
     * statement binding.
     *
     * <p>{@link Map} and {@link List} instances are serialized to JSON strings
     * via {@link Gson}, allowing sub-domain data and array fields to be stored
     * in {@code JSON} columns. All other values are passed through unchanged.</p>
     *
     * @param value the Java value to convert
     * @return the SQL-compatible value
     */
    private Object toSqlValue(final Object value) {
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return Constants.GSON.toJson(value);
        }

        return value;
    }

    /**
     * Maps a Java type to the corresponding MySQL column type.
     *
     * <p>Used during automatic table creation to infer column types from
     * the first data map written to a table. {@link Map} and {@link List}
     * values are mapped to {@code JSON} columns for sub-domain and array
     * field storage.</p>
     *
     * @param value the Java value to map
     * @return the MySQL column type string
     */
    private String toSqlType(final Object value) {
        if (value == null) {
            return "TEXT";
        }

        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return "JSON";
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