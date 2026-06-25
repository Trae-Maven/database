package io.github.trae.database.repository;

import io.github.trae.database.DatabaseApi;
import io.github.trae.database.constants.Constants;
import io.github.trae.database.domain.data.DomainData;
import io.github.trae.database.domain.models.DomainProperty;
import io.github.trae.database.domain.models.SubDomain;
import io.github.trae.database.domain.models.SubDomainProperty;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.index.Index;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.repository.interfaces.IAbstractRepository;
import io.github.trae.utilities.UtilGeneric;
import io.github.trae.utilities.UtilJava;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Base repository implementation providing all CRUD, query, and index operations.
 *
 * <p>Concrete repositories extend this class and typically contain no additional
 * logic — all boilerplate is handled here. The only required override is the
 * constructor (to pass the {@link DatabaseDriver} and the database/collection
 * names); {@link #registerIndexes()} may optionally be overridden to declare
 * indexes.</p>
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
 * public class AccountRepository extends AbstractRepository<Account, AccountProperty> {
 *
 *     public AccountRepository(final DatabaseDriver databaseDriver) {
 *         super(databaseDriver, "Admin", "Accounts");
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
 * @see DatabaseDriver
 */
public abstract class AbstractRepository<Domain extends io.github.trae.database.domain.models.Domain<Property>, Property extends Enum<?> & DomainProperty> implements IAbstractRepository<Domain, Property> {

    private final List<Index> indexList = new ArrayList<>();

    private final DatabaseDriver databaseDriver;

    @Getter
    private final String databaseName, collectionName;

    /**
     * Whether this repository has completed its initial data load.
     *
     * <p>Defaults to {@code false} and is checked by
     * {@link DatabaseApi#isDatabaseLoaded()} to determine global readiness.</p>
     */
    @Getter
    @Setter
    private boolean loaded;

    /**
     * Creates a new repository backed by the given driver and registers it
     * with {@link DatabaseApi} for global readiness tracking.
     *
     * @param databaseDriver the driver used for all database operations
     */
    public AbstractRepository(final DatabaseDriver databaseDriver, final String databaseName, final String collectionName) {
        this.databaseDriver = databaseDriver;

        this.databaseName = databaseName;
        this.collectionName = collectionName;

        DatabaseApi.addRepository(this);
    }

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
        final LinkedHashMap<String, Object> dataMap = this.toDataMap(domain, List.of(this.getClassOfProperty().getEnumConstants()));

        if (!(dataMap.isEmpty())) {
            this.databaseDriver.save(this.getDatabaseName(), this.getCollectionName(), domain.getId(), this.getFiltersByDomain(domain), dataMap);
        }
    }

    /**
     * Updates only the specified properties on the domain's existing record.
     *
     * @param domain       the domain instance containing the updated values
     * @param propertyList the properties to update
     */
    @Override
    public void update(final Domain domain, final List<Property> propertyList) {
        final LinkedHashMap<String, Object> dataMap = this.toDataMap(domain, propertyList);

        if (!(dataMap.isEmpty())) {
            this.databaseDriver.update(this.getDatabaseName(), this.getCollectionName(), domain.getId(), this.getFiltersByDomain(domain), dataMap);
        }
    }

    /**
     * Deletes the domain's record by its identifier.
     *
     * @param domain the domain instance to delete
     */
    @Override
    public void delete(final Domain domain) {
        this.databaseDriver.delete(this.getDatabaseName(), this.getCollectionName(), domain.getId(), this.getFiltersByDomain(domain));
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
     * Synchronously reads a single property value from one domain by its identifier.
     *
     * @param identifier the UUID to look up
     * @param property   the property to project and return
     * @return an {@link Optional} containing the property value, or empty if the domain or property is absent
     */
    @Override
    public Optional<Object> findOneByPropertySynchronously(final UUID identifier, final Property property) {
        return this.databaseDriver.findOneByPropertySynchronously(this.getDatabaseName(), this.getCollectionName(), identifier, property.name());
    }

    /**
     * Asynchronously reads a single property value from one domain by its identifier.
     *
     * @param identifier the UUID to look up
     * @param property   the property to project and return
     * @return a future resolving to an {@link Optional} containing the property value
     */
    @Override
    public CompletableFuture<Optional<Object>> findOneByPropertyAsynchronously(final UUID identifier, final Property property) {
        return this.databaseDriver.findOneByPropertyAsynchronously(this.getDatabaseName(), this.getCollectionName(), identifier, property.name());
    }

    /**
     * Synchronously reads several property values from one domain by its identifier.
     *
     * <p>Projects only the named properties and re-keys the driver's raw {@code String}
     * map back to {@link Property} constants via {@link #toPropertyMap}. Returns an empty
     * map if the domain is absent.</p>
     *
     * @param identifier   the UUID to look up
     * @param propertyList the properties to project and return
     * @return a property-to-value map, empty if the domain is not found
     */
    @Override
    public LinkedHashMap<Property, Object> findOneByManyPropertySynchronously(final UUID identifier, final List<Property> propertyList) {
        return this.databaseDriver.findOneByManyPropertySynchronously(this.getDatabaseName(), this.getCollectionName(), identifier, propertyList.stream().map(Property::name).toList())
                .map(this::toPropertyMap)
                .orElseGet(LinkedHashMap::new);
    }

    /**
     * Asynchronously reads several property values from one domain by its identifier.
     *
     * @param identifier   the UUID to look up
     * @param propertyList the properties to project and return
     * @return a future resolving to an {@link Optional} containing a property-to-value map
     */
    @Override
    public CompletableFuture<Optional<LinkedHashMap<Property, Object>>> findOneByManyPropertyAsynchronously(final UUID identifier, final List<Property> propertyList) {
        return this.databaseDriver.findOneByManyPropertyAsynchronously(this.getDatabaseName(), this.getCollectionName(), identifier, propertyList.stream().map(Property::name).toList())
                .thenApply(optional -> optional.map(this::toPropertyMap));
    }

    /**
     * Synchronously reads a single property value from many domains in one query.
     *
     * <p>Resolves all identifiers in a single round trip, returning a map from each found
     * identifier to its property value. Identifiers with no matching domain are absent.</p>
     *
     * @param identifierList the identifiers to resolve
     * @param property       the property to project and return for each
     * @return a map from identifier to its property value, empty if none match
     */
    @Override
    public LinkedHashMap<UUID, Object> findManyByOnePropertySynchronously(final List<UUID> identifierList, final Property property) {
        return this.databaseDriver.findManyByOnePropertySynchronously(this.getDatabaseName(), this.getCollectionName(), identifierList, property.name());
    }

    /**
     * Asynchronously reads a single property value from many domains in one query.
     *
     * @param identifierList the identifiers to resolve
     * @param property       the property to project and return for each
     * @return a future resolving to a map from identifier to its property value
     */
    @Override
    public CompletableFuture<LinkedHashMap<UUID, Object>> findManyByOnePropertyAsynchronously(final List<UUID> identifierList, final Property property) {
        return this.databaseDriver.findManyByOnePropertyAsynchronously(this.getDatabaseName(), this.getCollectionName(), identifierList, property.name());
    }

    /**
     * Synchronously reads several property values from many domains in one query.
     *
     * <p>Resolves all identifiers in a single round trip, returning a map from each found
     * identifier to its property-to-value map. Each inner map is re-keyed from the driver's
     * raw {@code String} keys back to {@link Property} constants via {@link #toPropertyMap}.</p>
     *
     * @param identifierList the identifiers to resolve
     * @param propertyList   the properties to project and return for each
     * @return a map from identifier to its property-to-value map, empty if none match
     */
    @Override
    public LinkedHashMap<UUID, LinkedHashMap<Property, Object>> findManyByManyPropertySynchronously(final List<UUID> identifierList, final List<Property> propertyList) {
        final LinkedHashMap<UUID, LinkedHashMap<String, Object>> raw = this.databaseDriver.findManyByManyPropertySynchronously(this.getDatabaseName(), this.getCollectionName(), identifierList, propertyList.stream().map(Property::name).toList());

        final LinkedHashMap<UUID, LinkedHashMap<Property, Object>> result = new LinkedHashMap<>();

        for (final Map.Entry<UUID, LinkedHashMap<String, Object>> entry : raw.entrySet()) {
            result.put(entry.getKey(), this.toPropertyMap(entry.getValue()));
        }

        return result;
    }

    /**
     * Asynchronously reads several property values from many domains in one query.
     *
     * @param identifierList the identifiers to resolve
     * @param propertyList   the properties to project and return for each
     * @return a future resolving to a map from identifier to its property-to-value map
     */
    @Override
    public CompletableFuture<LinkedHashMap<UUID, LinkedHashMap<Property, Object>>> findManyByManyPropertyAsynchronously(final List<UUID> identifierList, final List<Property> propertyList) {
        return this.databaseDriver.findManyByManyPropertyAsynchronously(this.getDatabaseName(), this.getCollectionName(), identifierList, propertyList.stream().map(Property::name).toList())
                .thenApply(raw -> {
                    final LinkedHashMap<UUID, LinkedHashMap<Property, Object>> result = new LinkedHashMap<>();

                    for (final Map.Entry<UUID, LinkedHashMap<String, Object>> entry : raw.entrySet()) {
                        result.put(entry.getKey(), this.toPropertyMap(entry.getValue()));
                    }

                    return result;
                });
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

    @Override
    public void registerIndexes() {

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
     * <p>Wraps the raw map in a {@link DomainData} (which extracts the {@code _id}
     * field internally), then reflectively invokes the domain's {@code DomainData}
     * constructor.</p>
     *
     * @param map the raw key-value data from the database driver
     * @return the constructed domain instance
     * @throws IllegalStateException if the domain class lacks a {@code DomainData} constructor
     */
    private Domain toDomain(final LinkedHashMap<String, Object> map) {
        try {
            final DomainData<Property> domainData = new DomainData<>(map);

            return this.getDomainTypeByData(domainData).getConstructor(DomainData.class).newInstance(domainData);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to construct domain from DomainData", e);
        }
    }

    /**
     * Serializes a domain's properties into a flat key-value map for storage.
     *
     * <p>Iterates the given property list and calls
     * {@link io.github.trae.database.domain.models.Domain#getValueByProperty}
     * on each to build the map. Values are serialized as follows:</p>
     * <ul>
     *     <li>{@link SubDomain} — recursively serialized via {@link #serializeSubDomain}</li>
     *     <li>{@link Map} containing {@link SubDomain} values — each entry's value is
     *         recursively serialized, preserving the original map keys</li>
     *     <li>All other values — passed through as-is</li>
     * </ul>
     *
     * @param domain       the domain instance to serialize
     * @param propertyList the properties to include in the map
     * @return the serialized data map
     */
    private LinkedHashMap<String, Object> toDataMap(final Domain domain, final List<Property> propertyList) {
        return UtilJava.createMap(new LinkedHashMap<>(), map -> {
            for (final Property property : propertyList) {
                Object value = domain.getValueByProperty(property);
                if (Constants.EMPTY_PROPERTY.equals(value)) {
                    continue;
                }

                value = this.serializeValue(value);

                map.put(property.name(), value);
            }
        });
    }

    /**
     * Serializes a single value, handling {@link SubDomain} instances,
     * {@link Map} values containing {@link SubDomain} entries, and
     * {@link Collection} values containing {@link SubDomain} entries.
     *
     * @param value the value to serialize
     * @return the serialized value, or the original if no serialization is needed
     */
    private Object serializeValue(final Object value) {
        if (value instanceof final SubDomain<?> subDomain) {
            return this.serializeSubDomain(subDomain);
        }

        if (value instanceof final Map<?, ?> mapValue && !mapValue.isEmpty() && mapValue.values().iterator().next() instanceof SubDomain<?>) {
            final LinkedHashMap<Object, Object> serializedMap = new LinkedHashMap<>();
            for (final Map.Entry<?, ?> entry : mapValue.entrySet()) {
                serializedMap.put(entry.getKey(), this.serializeSubDomain((SubDomain<?>) entry.getValue()));
            }
            return serializedMap;
        }

        return value;
    }

    /**
     * Recursively serializes a {@link SubDomain} into a nested key-value map.
     *
     * <p>Resolves the sub-domain's property enum class reflectively via
     * {@link UtilGeneric#getGenericParameter}, then iterates all enum constants
     * to build the map. The {@code _id} field is included as the sub-domain's
     * identifier. Nested sub-domains are serialized recursively.</p>
     *
     * @param subDomain     the sub-domain instance to serialize
     * @param <SubProperty> the property enum type of the sub-domain
     * @return the serialized nested data map
     */
    @SuppressWarnings("unchecked")
    private <SubProperty extends Enum<?> & SubDomainProperty> LinkedHashMap<String, Object> serializeSubDomain(final SubDomain<SubProperty> subDomain) {
        return UtilJava.createMap(new LinkedHashMap<>(), map -> {
            final Class<SubProperty> subPropertyClass = (Class<SubProperty>) UtilGeneric.getGenericParameter(subDomain.getClass(), SubDomain.class, 0);

            for (final SubProperty property : subPropertyClass.getEnumConstants()) {
                Object value = subDomain.getValueByProperty(property);

                if (value instanceof final SubDomain<?> nested) {
                    value = this.serializeSubDomain(nested);
                }

                map.put(property.name(), value);
            }
        });
    }

    /**
     * Re-keys a driver result map from raw property names back to {@link Property} constants.
     *
     * <p>The driver returns property keys as {@code String} (each a {@link Property#name()}).
     * This resolves each name to its enum constant via the property class returned by
     * {@link #getClassOfProperty()}, preserving the original iteration order and values.</p>
     *
     * <p>The raw-type cast on {@link Enum#valueOf} is required because {@code Property} is
     * declared as {@code Enum<?>} rather than {@code Enum<Property>}, so the compiler cannot
     * satisfy {@code valueOf}'s {@code <T extends Enum<T>>} bound; it is safe at runtime since
     * {@link #getClassOfProperty()} returns the concrete enum class.</p>
     *
     * @param raw the driver's property-name-to-value map
     * @return a map keyed by {@link Property} constants
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private LinkedHashMap<Property, Object> toPropertyMap(final LinkedHashMap<String, Object> raw) {
        final LinkedHashMap<Property, Object> result = new LinkedHashMap<>();

        for (final Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put((Property) Enum.valueOf((Class) this.getClassOfProperty(), entry.getKey()), entry.getValue());
        }

        return result;
    }
}