package io.github.trae.database.types.mongo;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import io.github.trae.database.batch.BatchQueue;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.index.Index;
import io.github.trae.database.index.IndexEntry;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.types.mongo.records.MongoWriteOperation;
import io.github.trae.utilities.UtilJava;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MongoDB implementation of the {@link DatabaseDriver} interface.
 *
 * <p>All write operations ({@link #save}, {@link #update}, {@link #delete}) produce
 * {@link WriteModel} instances that are collected by a {@link BatchQueue}. On flush,
 * the entire batch is grouped by database and collection, then executed as a single
 * {@code bulkWrite} call per collection — minimizing round trips to the server.</p>
 *
 * <p>Read operations execute synchronously against the MongoDB driver. Asynchronous
 * read variants delegate to the synchronous methods via {@link CompletableFuture#supplyAsync}.</p>
 *
 * <p>The {@code _id} field is used directly as the UUID primary key.</p>
 *
 * @see DatabaseDriver
 * @see BatchQueue
 * @see MongoWriteOperation
 */
public class MongoDatabaseDriver implements DatabaseDriver {

    private final String connectionString;
    private final BatchQueue<MongoWriteOperation> batchQueue;

    private MongoClient mongoClient;

    /**
     * Creates a new MongoDB driver with batched write support.
     *
     * @param connectionString the MongoDB connection URI
     * @param batchSize        the maximum number of write operations before auto-flush
     * @param period           the flush interval; {@link Duration#ZERO} for instant mode
     */
    public MongoDatabaseDriver(final String connectionString, final int batchSize, final Duration period) {
        this.connectionString = connectionString;
        this.batchQueue = new BatchQueue<>(batchSize, period, this::executeBatch);
    }

    /**
     * Opens the connection to MongoDB using the configured connection string.
     */
    @Override
    public void connect() {
        this.mongoClient = MongoClients.create(this.connectionString);
    }

    /**
     * Flushes all pending batched writes and closes the MongoDB client.
     */
    @Override
    public void disconnect() {
        this.batchQueue.shutdown();

        if (this.mongoClient != null) {
            this.mongoClient.close();
        }
    }

    /**
     * Queues an upsert operation for the given domain data.
     *
     * <p>Produces an {@link UpdateOneModel} with {@link UpdateOptions#upsert(boolean)}
     * set to {@code true}. If a filter list is provided, it is used as the match
     * condition; otherwise falls back to matching on {@code _id}.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap    the property name to value map
     */
    @Override
    public void save(final String database, final String collection, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        final List<Bson> updates = UtilJava.createCollection(new ArrayList<>(), list -> {
            for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
                list.add(Updates.set(entry.getKey(), entry.getValue()));
            }
        });

        this.batchQueue.add(new MongoWriteOperation(
                database,
                collection,
                new UpdateOneModel<>(
                        this.buildWriteFilter(identifier, filterList),
                        Updates.combine(updates),
                        new UpdateOptions().upsert(true)
                )
        ));
    }

    /**
     * Queues an update operation for the specified fields only.
     *
     * <p>Produces an {@link UpdateOneModel} without upsert. If a filter list is
     * provided, it is used as the match condition; otherwise falls back to
     * matching on {@code _id}.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList the filter conditions for matching, or empty/null to match on {@code _id}
     * @param dataMap    the property name to value map of fields to update
     */
    @Override
    public void update(final String database, final String collection, final UUID identifier, final List<Filter> filterList, final LinkedHashMap<String, Object> dataMap) {
        final List<Bson> updates = UtilJava.createCollection(new ArrayList<>(), list -> {
            for (final Map.Entry<String, Object> entry : dataMap.entrySet()) {
                list.add(Updates.set(entry.getKey(), entry.getValue()));
            }
        });

        this.batchQueue.add(new MongoWriteOperation(
                database,
                collection,
                new UpdateOneModel<>(
                        this.buildWriteFilter(identifier, filterList),
                        Updates.combine(updates)
                )
        ));
    }

    /**
     * Queues a delete operation for the document with the given identifier.
     *
     * <p>If a filter list is provided, it is used as the match condition;
     * otherwise falls back to matching on {@code _id}.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the domain's UUID ({@code _id})
     * @param filterList the filter conditions for matching, or empty/null to match on {@code _id}
     */
    @Override
    public void delete(final String database, final String collection, final UUID identifier, final List<Filter> filterList) {
        this.batchQueue.add(new MongoWriteOperation(
                database,
                collection,
                new DeleteOneModel<>(this.buildWriteFilter(identifier, filterList))
        ));
    }

    /**
     * Synchronously finds a single document by its {@code _id}.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the UUID to look up
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final UUID identifier) {
        final Document document = this.getCollection(database, collection).find(Filters.eq("_id", identifier)).first();

        return Optional.ofNullable(document).map(this::documentToMap);
    }

    /**
     * Synchronously finds a single document matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param filters    the filter conditions to apply
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final List<Filter> filters) {
        final Document document = this.getCollection(database, collection).find(this.buildFilters(filters)).first();

        return Optional.ofNullable(document).map(this::documentToMap);
    }

    /**
     * Synchronously finds a single document matching the given query options.
     *
     * <p>Applies sort and skip from the {@link QueryOptions} to the query iterable
     * before retrieving the first result.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param options    the query options including filters, sort, and skip
     * @return an {@link Optional} containing the raw data map, or empty if not found
     */
    @Override
    public Optional<LinkedHashMap<String, Object>> findOneSynchronously(final String database, final String collection, final QueryOptions options) {
        FindIterable<Document> iterable = this.getCollection(database, collection).find(this.buildFilters(options.getFilters()));

        if (options.getField() != null) {
            iterable = iterable.sort(this.buildSort(options.getField(), options.getSortDirection()));
        }

        if (options.getSkip() > 0) {
            iterable = iterable.skip(options.getSkip());
        }

        return Optional.ofNullable(iterable.first()).map(this::documentToMap);
    }

    /**
     * Synchronously finds all documents matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param filters    the filter conditions to apply
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String database, final String collection, final List<Filter> filters) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();

        this.getCollection(database, collection)
                .find(this.buildFilters(filters))
                .forEach(document -> results.add(this.documentToMap(document)));

        return results;
    }

    /**
     * Synchronously finds all documents matching the given query options.
     *
     * <p>Applies sort, skip, and limit from the {@link QueryOptions}
     * to the query iterable before iterating results.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param options    the query options including filters, sort, limit, and skip
     * @return a list of raw data maps, empty if no matches
     */
    @Override
    public List<LinkedHashMap<String, Object>> findManySynchronously(final String database, final String collection, final QueryOptions options) {
        final List<LinkedHashMap<String, Object>> results = new ArrayList<>();

        FindIterable<Document> iterable = this.getCollection(database, collection).find(this.buildFilters(options.getFilters()));

        if (options.getField() != null) {
            iterable = iterable.sort(this.buildSort(options.getField(), options.getSortDirection()));
        }

        if (options.getSkip() > 0) {
            iterable = iterable.skip(options.getSkip());
        }

        if (options.getLimit() > 0) {
            iterable = iterable.limit(options.getLimit());
        }

        iterable.forEach(document -> results.add(this.documentToMap(document)));
        return results;
    }

    /**
     * Asynchronously finds a single document by its {@code _id}.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the UUID to look up
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final UUID identifier) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, identifier));
    }

    /**
     * Asynchronously finds a single document matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param filters    the filter conditions to apply
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, filters));
    }

    /**
     * Asynchronously finds a single document matching the given query options.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param options    the query options including filters, sort, and skip
     * @return a future resolving to an {@link Optional} containing the raw data map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<String, Object>>> findOneAsynchronously(final String database, final String collection, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findOneSynchronously(database, collection, options));
    }

    /**
     * Asynchronously finds all documents matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param filters    the filter conditions to apply
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String database, final String collection, final List<Filter> filters) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(database, collection, filters));
    }

    /**
     * Asynchronously finds all documents matching the given query options.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param options    the query options including filters, sort, limit, and skip
     * @return a future resolving to a list of raw data maps
     */
    @Override
    public CompletableFuture<List<LinkedHashMap<String, Object>>> findManyAsynchronously(final String database, final String collection, final QueryOptions options) {
        return CompletableFuture.supplyAsync(() -> this.findManySynchronously(database, collection, options));
    }

    /**
     * Checks document existence using a projection-limited query on the {@code _id} index.
     *
     * <p>Projects only {@code _id} and limits to 1 result for maximum efficiency.</p>
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param identifier the UUID to check
     * @return {@code true} if the document exists
     */
    @Override
    public boolean exists(final String database, final String collection, final UUID identifier) {
        return this.getCollection(database, collection)
                .find(Filters.eq("_id", identifier))
                .projection(new Document("_id", 1))
                .limit(1)
                .first() != null;
    }

    /**
     * Returns the total number of documents in the collection.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @return the document count
     */
    @Override
    public long count(final String database, final String collection) {
        return this.getCollection(database, collection).countDocuments();
    }

    /**
     * Returns the number of documents matching the given filters.
     *
     * @param database   the target database name
     * @param collection the target collection name
     * @param filters    the filter conditions to apply
     * @return the matching document count
     */
    @Override
    public long count(final String database, final String collection, final List<Filter> filters) {
        return this.getCollection(database, collection).countDocuments(this.buildFilters(filters));
    }

    /**
     * Creates a compound index on the target collection.
     *
     * <p>Translates each {@link IndexEntry} into an ascending or descending
     * index key, then combines them via {@link Indexes#compoundIndex(java.util.List)}.</p>
     *
     * @param databaseName the target database name
     * @param collection   the target collection name
     * @param index        the index definition
     */
    @Override
    public void createIndex(final String databaseName, final String collection, final Index index) {
        final List<Bson> indexFields = UtilJava.createCollection(new ArrayList<>(), list -> {
            for (final IndexEntry entry : index.getEntries()) {
                switch (entry.getDirection()) {
                    case ASCENDING -> list.add(Indexes.ascending(entry.getField()));
                    case DESCENDING -> list.add(Indexes.descending(entry.getField()));
                }
            }
        });

        this.getCollection(databaseName, collection).createIndex(Indexes.compoundIndex(indexFields), new IndexOptions().unique(index.isUnique()));
    }

    /**
     * Executes a batch of write operations as grouped {@code bulkWrite} calls.
     *
     * <p>Operations are grouped by {@code databaseName.collectionName}, then each
     * group's {@link WriteModel} list is executed as a single bulk write — one
     * round trip per collection regardless of batch size.</p>
     *
     * @param operations the batch of write operations to execute
     */
    private void executeBatch(final List<MongoWriteOperation> operations) {
        final LinkedHashMap<String, List<WriteModel<Document>>> grouped = new LinkedHashMap<>();

        for (final MongoWriteOperation operation : operations) {
            final String key = "%s.%s".formatted(operation.databaseName(), operation.collectionName());

            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(operation.writeModel());
        }

        for (final Map.Entry<String, List<WriteModel<Document>>> entry : grouped.entrySet()) {
            final String[] parts = entry.getKey().split("\\.", 2);
            this.getCollection(parts[0], parts[1]).bulkWrite(entry.getValue());
        }
    }

    /**
     * Resolves a {@link MongoCollection} handle for the given database and collection name.
     *
     * @param database   the database name
     * @param collection the collection name
     * @return the MongoDB collection handle
     */
    private MongoCollection<Document> getCollection(final String database, final String collection) {
        return this.mongoClient.getDatabase(database).getCollection(collection);
    }

    /**
     * Converts a MongoDB {@link Document} to a {@link LinkedHashMap}.
     *
     * @param document the source document
     * @return a mutable map containing the document's key-value pairs
     */
    private LinkedHashMap<String, Object> documentToMap(final Document document) {
        return new LinkedHashMap<>(document);
    }

    /**
     * Builds the filter for write operations (save, update, delete).
     *
     * <p>If the filter list is non-empty, it is compiled into a compound filter
     * using {@link #buildFilters}. Otherwise, falls back to an {@code _id} match
     * on the given identifier.</p>
     *
     * @param identifier the domain's UUID ({@code _id}), used as fallback
     * @param filterList the filter conditions, or null/empty to match on {@code _id}
     * @return the compiled BSON filter
     */
    private Bson buildWriteFilter(final UUID identifier, final List<Filter> filterList) {
        if (filterList != null && !filterList.isEmpty()) {
            return this.buildFilters(filterList);
        }

        return Filters.eq("_id", identifier);
    }

    /**
     * Translates a list of {@link Filter} instances into a combined MongoDB {@link Bson} filter.
     *
     * <p>Returns an empty {@link Document} if the filter list is null or empty,
     * otherwise combines all filters with {@link Filters#and}.</p>
     *
     * @param filters the filter conditions
     * @return the combined BSON filter
     */
    private Bson buildFilters(final List<Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return new Document();
        }

        final List<Bson> bsonFilters = new ArrayList<>();

        for (final Filter filter : filters) {
            bsonFilters.add(this.toMongoFilter(filter));
        }

        return Filters.and(bsonFilters);
    }

    /**
     * Translates a single {@link Filter} into its native MongoDB {@link Bson} equivalent.
     *
     * @param filter the filter to translate
     * @return the native MongoDB filter
     */
    private Bson toMongoFilter(final Filter filter) {
        final String field = filter.getField();
        final Object value = filter.getValue();

        return switch (filter.getOperator()) {
            case EQUALS -> Filters.eq(field, value);
            case NOT_EQUALS -> Filters.ne(field, value);
            case GREATER_THAN -> Filters.gt(field, value);
            case GREATER_THAN_OR_EQUALS -> Filters.gte(field, value);
            case LESS_THAN -> Filters.lt(field, value);
            case LESS_THAN_OR_EQUALS -> Filters.lte(field, value);
            case IN -> Filters.in(field, (Collection<?>) value);
            case NOT_IN -> Filters.nin(field, (Collection<?>) value);
            case EXISTS -> Filters.exists(field, (Boolean) value);
            case REGEX -> Filters.regex(field, (String) value);
        };
    }

    /**
     * Builds a MongoDB sort specification from a field name and direction.
     *
     * @param field     the field to sort by
     * @param direction the sort direction
     * @return the BSON sort specification
     */
    private Bson buildSort(final String field, final SortDirection direction) {
        return switch (direction) {
            case ASCENDING -> Sorts.ascending(field);
            case DESCENDING -> Sorts.descending(field);
        };
    }
}