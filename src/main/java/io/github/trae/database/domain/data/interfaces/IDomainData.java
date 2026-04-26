package io.github.trae.database.domain.data.interfaces;

import io.github.trae.database.domain.data.DomainData;
import io.github.trae.database.domain.models.SharedDomainProperty;
import io.github.trae.database.domain.models.SubDomainProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public interface IDomainData<Property extends Enum<?> & SharedDomainProperty> {

    <Type> Type get(final Class<Type> clazz, final Property property, final Type defaultValue);

    default <Type> Type get(final Class<Type> clazz, final Property property) {
        return this.get(clazz, property, null);
    }

    <Type> List<Type> getList(final Class<Type> clazz, final Property property);

    <Key, Value> LinkedHashMap<Key, Value> getMap(final Class<Key> keyClass, final Class<Value> valueClass, final Property property);

    boolean contains(final Property property);

    <SubProperty extends Enum<?> & SubDomainProperty, SubDomain> LinkedHashMap<UUID, SubDomain> getSubDomainMap(final Property property, final Function<DomainData<SubProperty>, SubDomain> constructor);
}