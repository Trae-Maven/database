package io.github.trae.database.repository.interfaces;

import io.github.trae.database.domain.data.DomainData;
import io.github.trae.database.domain.models.DomainProperty;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.index.Index;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.utilities.UtilGeneric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface IAbstractRepository<Domain extends io.github.trae.database.domain.models.Domain<Property>, Property extends Enum<?> & DomainProperty> {

    @SuppressWarnings("unchecked")
    default Class<Domain> getClassOfDomain() {
        return (Class<Domain>) UtilGeneric.getGenericParameter(this.getClass(), IAbstractRepository.class, 0);
    }

    @SuppressWarnings("unchecked")
    default Class<Property> getClassOfProperty() {
        return (Class<Property>) UtilGeneric.getGenericParameter(this.getClass(), IAbstractRepository.class, 1);
    }

    default Class<? extends Domain> getDomainTypeByData(final DomainData<Property> domainData) {
        return this.getClassOfDomain();
    }

    default List<Filter> getFiltersByDomain(final Domain domain) {
        return Collections.emptyList();
    }

    void save(final Domain domain);

    void update(final Domain domain, final List<Property> propertyList);

    default void update(final Domain domain, final Property property) {
        this.update(domain, Collections.singletonList(property));
    }

    void delete(final Domain domain);

    Optional<Domain> findOneSynchronously(final UUID identifier);

    Optional<Domain> findOneSynchronously(final List<Filter> filters);

    Optional<Domain> findOneSynchronously(final QueryOptions queryOptions);

    List<Domain> findManySynchronously(final List<Filter> filters);

    List<Domain> findManySynchronously(final QueryOptions queryOptions);

    CompletableFuture<Optional<Domain>> findOneAsynchronously(final UUID identifier);

    CompletableFuture<Optional<Domain>> findOneAsynchronously(final List<Filter> filters);

    CompletableFuture<Optional<Domain>> findOneAsynchronously(final QueryOptions queryOptions);

    CompletableFuture<List<Domain>> findManyAsynchronously(final List<Filter> filters);

    CompletableFuture<List<Domain>> findManyAsynchronously(final QueryOptions queryOptions);

    Optional<Object> findOneByPropertySynchronously(final UUID identifier, final Property property);

    CompletableFuture<Optional<Object>> findOneByPropertyAsynchronously(final UUID identifier, final Property property);

    LinkedHashMap<Property, Object> findOneByManyPropertySynchronously(final UUID identifier, final List<Property> propertyList);

    CompletableFuture<Optional<LinkedHashMap<Property, Object>>> findOneByManyPropertyAsynchronously(final UUID identifier, final List<Property> propertyList);

    LinkedHashMap<UUID, Object> findManyByOnePropertySynchronously(final List<UUID> identifierList, final Property property);

    CompletableFuture<LinkedHashMap<UUID, Object>> findManyByOnePropertyAsynchronously(final List<UUID> identifierList, final Property property);

    LinkedHashMap<UUID, LinkedHashMap<Property, Object>> findManyByManyPropertySynchronously(final List<UUID> identifierList, final List<Property> propertyList);

    CompletableFuture<LinkedHashMap<UUID, LinkedHashMap<Property, Object>>> findManyByManyPropertyAsynchronously(final List<UUID> identifierList, final List<Property> propertyList);

    boolean exists(final UUID identifier);

    long count();

    long count(final List<Filter> filters);

    void registerIndexes();

    void addIndex(final Index index);

    List<Index> getIndexes();

    void applyIndexes();
}