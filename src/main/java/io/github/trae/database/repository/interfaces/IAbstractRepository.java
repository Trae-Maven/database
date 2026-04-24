package io.github.trae.database.repository.interfaces;

import io.github.trae.database.domain.models.DomainProperty;
import io.github.trae.database.filter.Filter;
import io.github.trae.database.index.Index;
import io.github.trae.database.query.QueryOptions;
import io.github.trae.database.repository.annotations.Repository;
import io.github.trae.utilities.UtilGeneric;

import java.util.Collections;
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

    default String getDatabaseName() {
        return this.getClass().getAnnotation(Repository.class).databaseName();
    }

    default String getCollectionName() {
        return this.getClass().getAnnotation(Repository.class).collectionName();
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

    boolean exists(final UUID identifier);

    long count();

    long count(final List<Filter> filters);

    void registerIndexes();

    void addIndex(final Index index);

    List<Index> getIndexes();

    void applyIndexes();
}