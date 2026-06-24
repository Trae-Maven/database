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
 * projected property reads, existence checks, counting, and index management. Each
 * supported database type (MongoDB, MySQL, etc.) provides its own implementation,
 * translating these generic operations into native driver calls.</p>
 *
 * <p>Write operations ({@link #save}, {@link #update}, {@link #delete}) are typically
 * routed through a {@link io.github.trae.database.batch.BatchQueue} by the
 * implementation for batched execution.</p>
 *
 * <p>All read methods return data as {@link LinkedHashMap} instances (and, for the
 * property-projection methods, as plain {@link Object} values or maps thereof). Nested
 * structures — sub-documents and arrays — are recursively normalized to
 * {@link LinkedHashMap} and {@link List} so the shape is identical regardless of the
 * underlying backend, as required by
 * {@link io.github.trae.database.domain.data.DomainData} during deserialization.</p>
 *
 * <p>The repository layer interacts exclusively through this interface,
 * making the underlying database technology fully interchangeable.</p>
 *
 * @see io.github.trae.database.types.mongo.MongoDatabaseDriver
 * @see io.github.trae.database.types.mysql.MySqlDatabaseDriver
 */
public interface DatabaseDriver extends Connector {

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
     * Synchronously reads a single projected property from one document or row by its identifier.
     *
     * <p>Only the named property is fetched — implementations project to that single
     * field for a cheap, narrow read. Nested values are normalized to
     * {@link LinkedHashMap} / {@link List}.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @param propertyKey    the property to project and return
     * @return an {@link Optional} containing the property value, or empty if the document or property is absent
     */
    Optional<Object> findOneByPropertySynchronously(final String databaseName, final String collectionName, final UUID identifier, final String propertyKey);

    /**
     * Asynchronously reads a single projected property from one document or row by its identifier.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifier     the domain's unique identifier ({@code _id})
     * @param propertyKey    the property to project and return
     * @return a future resolving to an {@link Optional} containing the property value
     */
    CompletableFuture<Optional<Object>> findOneByPropertyAsynchronously(final String databaseName, final String collectionName, final UUID identifier, final String propertyKey);

    /**
     * Synchronously reads several projected properties from one document or row by its identifier.
     *
     * <p>Only the named properties are fetched. The returned map preserves the
     * requested key order; missing properties map to {@code null}. Nested values
     * are normalized to {@link LinkedHashMap} / {@link List}.</p>
     *
     * @param databaseName    the target database name
     * @param collectionName  the target collection or table name
     * @param identifier      the domain's unique identifier ({@code _id})
     * @param propertyKeyList the properties to project and return
     * @return an {@link Optional} containing a property-to-value map, or empty if the document is absent
     */
    Optional<LinkedHashMap<String, Object>> findOneByManyPropertySynchronously(final String databaseName, final String collectionName, final UUID identifier, final List<String> propertyKeyList);

    /**
     * Asynchronously reads several projected properties from one document or row by its identifier.
     *
     * @param databaseName    the target database name
     * @param collectionName  the target collection or table name
     * @param identifier      the domain's unique identifier ({@code _id})
     * @param propertyKeyList the properties to project and return
     * @return a future resolving to an {@link Optional} containing a property-to-value map
     */
    CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneByManyPropertyAsynchronously(final String databaseName, final String collectionName, final UUID identifier, final List<String> propertyKeyList);

    /**
     * Synchronously reads a single projected property from many documents or rows in one query.
     *
     * <p>Resolves all given identifiers in a single round trip, returning a map from
     * each found identifier to its projected property value. Identifiers with no
     * matching document are simply absent from the result. Nested values are
     * normalized to {@link LinkedHashMap} / {@link List}.</p>
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifierList the identifiers to resolve
     * @param propertyKey    the property to project and return for each
     * @return a map from identifier to its property value, empty if none match or the input list is empty
     */
    LinkedHashMap<UUID, Object> findManyByOnePropertySynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final String propertyKey);

    /**
     * Asynchronously reads a single projected property from many documents or rows in one query.
     *
     * @param databaseName   the target database name
     * @param collectionName the target collection or table name
     * @param identifierList the identifiers to resolve
     * @param propertyKey    the property to project and return for each
     * @return a future resolving to a map from identifier to its property value
     */
    CompletableFuture<LinkedHashMap<UUID, Object>> findManyByOnePropertyAsynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final String propertyKey);

    /**
     * Synchronously reads several projected properties from many documents or rows in one query.
     *
     * <p>Resolves all given identifiers in a single round trip, returning a map from
     * each found identifier to a property-to-value map. Each inner map preserves the
     * requested key order; missing properties map to {@code null}. Identifiers with no
     * matching document are absent from the outer result. Nested values are normalized
     * to {@link LinkedHashMap} / {@link List}.</p>
     *
     * @param databaseName    the target database name
     * @param collectionName  the target collection or table name
     * @param identifierList  the identifiers to resolve
     * @param propertyKeyList the properties to project and return for each
     * @return a map from identifier to its property-to-value map, empty if none match or the input list is empty
     */
    LinkedHashMap<UUID, LinkedHashMap<String, Object>> findManyByManyPropertySynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final List<String> propertyKeyList);

    /**
     * Asynchronously reads several projected properties from many documents or rows in one query.
     *
     * @param databaseName    the target database name
     * @param collectionName  the target collection or table name
     * @param identifierList  the identifiers to resolve
     * @param propertyKeyList the properties to project and return for each
     * @return a future resolving to a map from identifier to its property-to-value map
     */
    CompletableFuture<LinkedHashMap<UUID, LinkedHashMap<String, Object>>> findManyByManyPropertyAsynchronously(final String databaseName, final String collectionName, final List<UUID> identifierList, final List<String> propertyKeyList);

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