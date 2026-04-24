package io.github.trae.database.repository;

import io.github.trae.database.domain.data.DomainData;
import io.github.trae.database.domain.models.DomainProperty;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.index.Index;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.repository.interfaces.IAbstractRepository;
import io.github.trae.utilities.UtilJava;
import lombok.AllArgsConstructor;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Base repository implementation providing all CRUD, query, and index operations.
 *
 * <p>Concrete repositories extend this class and typically contain no additional
 * logic — all boilerplate is handled here. The only required overrides are the
 * constructor (to pass the {@link DatabaseDriver}) and optionally
 * {@link #registerIndexes()} to declare indexes.</p>
 *
 * <p>Write operations delegate to the driver which routes them through the
 * {@link io.github.trae.database.batch.BatchQueue} for batched execution.
 * Read operations execute synchronously or asynchronously depending on the
 * method variant called.</p>
 *
 * <p>Domain mapping is handled internally:</p>
 * <ul>
 *     <li>{@link #toDomain} — converts a raw {@link LinkedHashMap} from the driver
 *         into a domain object by wrapping it in {@link DomainData} and reflectively
 *         invoking the domain's {@code DomainData} constructor</li>
 *     <li>{@link #toDataMap} — converts a domain object into a {@link LinkedHashMap}
 *         by iterating the property enum constants and calling
 *         {@link io.github.trae.database.domain.models.Domain#getValueByProperty}</li>
 * </ul>
 *
 * <p>Example concrete repository:</p>
 * <pre>{@code
 * @Repository(databaseName = "Admin", collectionName = "Accounts")
 * public class AccountRepository extends AbstractRepository<Account, AccountProperty> {
 *
 *     public AccountRepository(final DatabaseDriver databaseDriver) {
 *         super(databaseDriver);
 *     }
 *
 *     @Override
 *     public void registerIndexes() {
 *         this.addIndex(new Index().on("EMAIL", SortDirection.ASCENDING).unique());
 *     }
 * }
 * }</pre>
 *
 * @param <Domain>   the domain type this repository manages
 * @param <Property> the property enum type defining the domain's fields
 * @see IAbstractRepository
 * @see io.github.trae.database.repository.annotations.Repository
 * @see DatabaseDriver
 */
@AllArgsConstructor
public abstract class AbstractRepository<Domain extends io.github.trae.database.domain.models.Domain<Property>, Property extends Enum<?> & DomainProperty> implements IAbstractRepository<Domain, Property> {

    private final List<Index> indexList = new ArrayList<>();

    private final DatabaseDriver databaseDriver;

    /**
     * Saves the domain by serializing all properties and delegating to the driver.
     *
     * <p>Uses upsert semantics — inserts if the identifier does not exist,
     * updates all fields if it does.</p>
     *
     * @param domain the domain instance to persist
     */
    @Override
    public void save(final Domain domain) {
        this.databaseDriver.save(this.getDatabaseName(), this.getCollectionName(), domain.getId(), this.toDataMap(domain, List.of(this.getClassOfProperty().getEnumConstants())));
    }

    /**
     * Updates only the specified properties on the domain's existing record.
     *
     * @param domain       the domain instance containing the updated values
     * @param propertyList the properties to update
     */
    @Override
    public void update(final Domain domain, final List<Property> propertyList) {
        this.databaseDriver.update(this.getDatabaseName(), this.getCollectionName(), domain.getId(), this.toDataMap(domain, propertyList));
    }

    /**
     * Deletes the domain's record by its identifier.
     *
     * @param domain the domain instance to delete
     */
    @Override
    public void delete(final Domain domain) {
        this.databaseDriver.delete(this.getDatabaseName(), this.getCollectionName(), domain.getId());
    }

    /**
     * Synchronously finds a domain by its unique identifier.
     *
     * @param identifier the UUID to look up
     * @return an {@link Optional} containing the domain, or empty if not found
     */
    @Override
    public Optional<Domain> findOneSynchronously(final UUID identifier) {
        return this.databaseDriver.findOneSynchronously(this.getDatabaseName(), this.getCollectionName(), identifier).map(this::toDomain);
    }

    /**
     * Synchronously finds the first domain matching the given filters.
     *
     * @param filters the filter conditions
     * @return an {@link Optional} containing the first match, or empty if none
     */
    @Override
    public Optional<Domain> findOneSynchronously(final List<Filter> filters) {
        return this.databaseDriver.findOneSynchronously(this.getDatabaseName(), this.getCollectionName(), filters).map(this::toDomain);
    }

    /**
     * Synchronously finds the first domain matching the given query options.
     *
     * @param options the query options including filters, sort, and skip
     * @return an {@link Optional} containing the first match, or empty if none
     */
    @Override
    public Optional<Domain> findOneSynchronously(final QueryOptions options) {
        return this.databaseDriver.findOneSynchronously(this.getDatabaseName(), this.getCollectionName(), options).map(this::toDomain);
    }

    /**
     * Synchronously finds all domains matching the given filters.
     *
     * @param filters the filter conditions
     * @return a list of matching domains, empty if none
     */
    @Override
    public List<Domain> findManySynchronously(final List<Filter> filters) {
        return this.databaseDriver.findManySynchronously(this.getDatabaseName(), this.getCollectionName(), filters).stream().map(this::toDomain).toList();
    }

    /**
     * Synchronously finds all domains matching the given query options.
     *
     * @param options the query options including filters, sort, limit, and skip
     * @return a list of matching domains, empty if none
     */
    @Override
    public List<Domain> findManySynchronously(final QueryOptions options) {
        return this.databaseDriver.findManySynchronously(this.getDatabaseName(), this.getCollectionName(), options).stream().map(this::toDomain).toList();
    }

    /**
     * Asynchronously finds a domain by its unique identifier.
     *
     * @param identifier the UUID to look up
     * @return a future resolving to an {@link Optional} containing the domain
     */
    @Override
    public CompletableFuture<Optional<Domain>> findOneAsynchronously(final UUID identifier) {
        return this.databaseDriver.findOneAsynchronously(this.getDatabaseName(), this.getCollectionName(), identifier).thenApply(optional -> optional.map(this::toDomain));
    }

    /**
     * Asynchronously finds the first domain matching the given filters.
     *
     * @param filters the filter conditions
     * @return a future resolving to an {@link Optional} containing the first match
     */
    @Override
    public CompletableFuture<Optional<Domain>> findOneAsynchronously(final List<Filter> filters) {
        return this.databaseDriver.findOneAsynchronously(this.getDatabaseName(), this.getCollectionName(), filters).thenApply(optional -> optional.map(this::toDomain));
    }

    /**
     * Asynchronously finds the first domain matching the given query options.
     *
     * @param options the query options including filters, sort, and skip
     * @return a future resolving to an {@link Optional} containing the first match
     */
    @Override
    public CompletableFuture<Optional<Domain>> findOneAsynchronously(final QueryOptions options) {
        return this.databaseDriver.findOneAsynchronously(this.getDatabaseName(), this.getCollectionName(), options).thenApply(optional -> optional.map(this::toDomain));
    }

    /**
     * Asynchronously finds all domains matching the given filters.
     *
     * @param filters the filter conditions
     * @return a future resolving to a list of matching domains
     */
    @Override
    public CompletableFuture<List<Domain>> findManyAsynchronously(final List<Filter> filters) {
        return this.databaseDriver.findManyAsynchronously(this.getDatabaseName(), this.getCollectionName(), filters).thenApply(list -> list.stream().map(this::toDomain).toList());
    }

    /**
     * Asynchronously finds all domains matching the given query options.
     *
     * @param options the query options including filters, sort, limit, and skip
     * @return a future resolving to a list of matching domains
     */
    @Override
    public CompletableFuture<List<Domain>> findManyAsynchronously(final QueryOptions options) {
        return this.databaseDriver.findManyAsynchronously(this.getDatabaseName(), this.getCollectionName(), options).thenApply(list -> list.stream().map(this::toDomain).toList());
    }

    /**
     * Checks whether a domain with the given identifier exists in the collection.
     *
     * @param identifier the UUID to check
     * @return {@code true} if the identifier exists
     */
    @Override
    public boolean exists(final UUID identifier) {
        return this.databaseDriver.exists(this.getDatabaseName(), this.getCollectionName(), identifier);
    }

    /**
     * Returns the total number of records in this repository's collection.
     *
     * @return the total count
     */
    @Override
    public long count() {
        return this.databaseDriver.count(this.getDatabaseName(), this.getCollectionName());
    }

    /**
     * Returns the number of records matching the given filters.
     *
     * @param filters the filter conditions
     * @return the matching count
     */
    @Override
    public long count(final List<Filter> filters) {
        return this.databaseDriver.count(this.getDatabaseName(), this.getCollectionName(), filters);
    }

    /**
     * Registers an index to be applied when {@link #applyIndexes()} is called.
     *
     * @param index the index definition to register
     */
    @Override
    public void addIndex(final Index index) {
        this.indexList.add(index);
    }

    /**
     * Returns an unmodifiable view of all registered indexes.
     *
     * @return the list of registered {@link Index} instances
     */
    @Override
    public List<Index> getIndexes() {
        return Collections.unmodifiableList(this.indexList);
    }

    /**
     * Applies all registered indexes to the database via the driver.
     *
     * <p>Should be called after {@link #registerIndexes()} has populated
     * the index list, typically during application startup.</p>
     */
    @Override
    public void applyIndexes() {
        for (final Index index : this.indexList) {
            this.databaseDriver.createIndex(this.getDatabaseName(), this.getCollectionName(), index);
        }
    }

    /**
     * Converts a raw database result map into a typed domain instance.
     *
     * <p>Extracts the {@code _id} field as a {@link UUID}, wraps the remaining
     * data in a {@link DomainData}, then reflectively invokes the domain's
     * constructor that accepts {@code DomainData}.</p>
     *
     * @param map the raw key-value data from the database driver
     * @return the constructed domain instance
     * @throws IllegalStateException if the domain class lacks a {@code DomainData} constructor
     */
    private Domain toDomain(final LinkedHashMap<String, Object> map) {
        try {
            return getClassOfDomain().getConstructor(DomainData.class).newInstance(new DomainData<>(UtilJava.cast(UUID.class, map.remove("_id")), map));
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to construct domain from DomainData", e);
        }
    }

    /**
     * Serializes a domain's properties into a flat key-value map for storage.
     *
     * <p>Iterates the given property list and calls
     * {@link io.github.trae.database.domain.models.Domain#getValueByProperty}
     * on each to build the map.</p>
     *
     * @param domain       the domain instance to serialize
     * @param propertyList the properties to include in the map
     * @return the serialized data map
     */
    private LinkedHashMap<String, Object> toDataMap(final Domain domain, final List<Property> propertyList) {
        return UtilJava.createMap(new LinkedHashMap<>(), map -> {
            for (final Property property : propertyList) {
                map.put(property.name(), domain.getValueByProperty(property));
            }
        });
    }
}