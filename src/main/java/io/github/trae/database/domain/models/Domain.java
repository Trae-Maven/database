package io.github.trae.database.domain.models;

import java.util.UUID;

/**
 * Core domain model interface representing a persistable entity.
 *
 * <p>Every domain object has a unique {@link UUID} identifier and exposes
 * its field values through a property enum. This contract enables the
 * repository layer to generically serialize any domain into a flat
 * key-value map for database storage without reflection on the domain's
 * own fields.</p>
 *
 * <p>The reverse mapping — database result to domain — is handled by
 * providing a constructor that accepts {@link io.github.trae.database.domain.data.DomainData}.</p>
 *
 * @param <Property> the property enum type defining this domain's persistable fields
 * @see DomainProperty
 * @see io.github.trae.database.domain.data.DomainData
 */
public interface Domain<Property extends Enum<?> & DomainProperty> {

    /**
     * Returns the unique identifier for this domain instance.
     *
     * @return the domain's UUID, used as the primary key ({@code _id}) in storage
     */
    UUID getId();

    /**
     * Returns the current value of the given property on this domain.
     *
     * <p>Used by the repository's serialization layer to build the data map
     * for save and update operations.</p>
     *
     * @param property the property to read
     * @return the current value, or {@code null} if unset
     */
    Object getValueByProperty(final Property property);
}