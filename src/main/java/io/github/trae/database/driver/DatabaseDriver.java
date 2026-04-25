package io.github.trae.database.driver;

import io.github.trae.database.filter.Filter;
import io.github.trae.database.index.Index;
import io.github.trae.database.query.QueryOptions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Backend-agnostic database driver interface.
 *
 * <p>Defines the contract for all database operations — writes, reads (sync and async),
 * existence checks, counting, and index management. Each supported database type
 * (MongoDB, MySQL, etc.) provides its own implementation, translating these
 * generic operations into native driver calls.</p>
 *
 * <p>Write operations ({@link #save}, {@link #update}, {@link #delete}) are typically
 * routed through a {@link io.github.trae.database.batch.BatchQueue} by the
 * implementation for batched execution.</p>
 *
 * <p>The repository layer interacts exclusively through this interface,
 * making the underlying database technology fully interchangeable.</p>
 *
 * @see io.github.trae.database.types.mongo.MongoDatabaseDriver
 * @see io.github.trae.database.types.mysql.MySqlDatabaseDriver
 */
public interface DatabaseDriver {

    /**
     * Opens the connection to the database.
     */
    void connect();

    /**
     * Flushes any pending batched operations and closes the database connection.
     */
    void disconnect();

    /**
     * Persists a domain's data, upserting if the identifier already exists.
     *
     * <p>If a filter list is provided, the implementation uses it as the match
     * condition for the upsert instead of the identifier. This enables compound
     * key upserts (e.g. matching on {@code serverId + username} rather than
     * {@code _id}).</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @param filterList     the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap        the property name to value map to persist
     */
    void save(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap);

    /**
     * Updates specific fields on an existing document or row.
     *
     * <p>If a filter list is provided, the implementation uses it as the match
     * condition for the update instead of the identifier.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @param filterList     the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap        the property name to value map of fields to update
     */
    void update(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap);

    /**
     * Deletes a document or row.
     *
     * <p>If a filter list is provided, the implementation uses it as the match
     * condition for the deletion instead of the identifier.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @param filterList     the filter conditions for matching, or empty/null to match on {@code _id}
     */
    void delete(final String databaseName, final String collectionName, final UUID identifier, final List<Filter> filterList);

    /**
     * Synchronously finds a single document or row by its identifier.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final UUID identifier);

    /**
     * Synchronously finds a single document or row matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param filters        the filter conditions to apply
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final List<Filter> filters);

    /**
     * Synchronously finds a single document or row matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param queryOptions   the query options including filters, sort, and skip
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String databaseName, final String collectionName, final QueryOptions queryOptions);

    /**
     * Synchronously finds all documents or rows matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param filters        the filter conditions to apply
     * @return a list of raw data maps, empty if no matches
     */
    List<LinkedHashMap<String, Object>> findManySynchronously(final String databaseName, final String collectionName, final List<Filter> filters);

    /**
     * Synchronously finds all documents or rows matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param queryOptions   the query options including filters, sort, limit, and skip
     * @return a list of raw data maps, empty if no matches
     */
    List<LinkedHashMap<String, Object>> findManySynchronously(final String databaseName, final String collectionName, final QueryOptions queryOptions);

    /**
     * Asynchronously finds a single document or row by its identifier.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final UUID identifier);

    /**
     * Asynchronously finds a single document or row matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param filters        the filter conditions to apply
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final List<Filter> filters);

    /**
     * Asynchronously finds a single document or row matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param queryOptions   the query options including filters, sort, and skip
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String databaseName, final String collectionName, final QueryOptions queryOptions);

    /**
     * Asynchronously finds all documents or rows matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param filters        the filter conditions to apply
     * @return a future resolving to a list of raw data maps
     */
    CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String databaseName, final String collectionName, final List<Filter> filters);

    /**
     * Asynchronously finds all documents or rows matching the given query options.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param queryOptions   the query options including filters, sort, limit, and skip
     * @return a future resolving to a list of raw data maps
     */
    CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String databaseName, final String collectionName, final QueryOptions queryOptions);

    /**
     * Checks whether a document or row with the given identifier exists.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @return {@code true} if the identifier exists in the collection
     */
    boolean exists(final String databaseName, final String collectionName, final UUID identifier);

    /**
     * Returns the total number of documents or rows in the collection.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @return the document or row count
     */
    long count(final String databaseName, final String collectionName);

    /**
     * Returns the number of documents or rows matching the given filters.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param filters        the filter conditions to apply
     * @return the matching document or row count
     */
    long count(final String databaseName, final String collectionName, final List<Filter> filters);

    /**
     * Creates an index on the target collection or table.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param index          the index definition including fields, direction, and uniqueness
     */
    void createIndex(final String databaseName, final String collectionName, final Index index);
}