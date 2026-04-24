package io.github.trae.database.domain.data.interfaces;

import io.github.trae.database.domain.models.DomainProperty;

public interface IDomainData<Property extends Enum<?> & DomainProperty> {

    <ReturnType> ReturnType get(final Class<ReturnType> clazz, final Property property, final ReturnType defaultValue);

    default <ReturnType> ReturnType get(final Class<ReturnType> clazz, final Property property) {
        return this.get(clazz, property, null);
    }

    boolean contains(final Property property);
}