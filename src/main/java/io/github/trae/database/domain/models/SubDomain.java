package io.github.trae.database.domain.models;

import java.util.UUID;

/**
 * Domain model interface representing an embeddable sub-document within a parent
 * {@link Domain}.
 *
 * <p>Sub-domains are serialized as nested maps keyed by their {@link UUID} identifier
 * within the parent document. Unlike top-level domains, sub-domains do not have their
 * own collection or repository — they are persisted as part of the parent's data.</p>
 *
 * <p>The serialization and deserialization of sub-domains is handled by the repository
 * layer via {@link io.github.trae.database.repository.AbstractRepository}
 * and {@link io.github.trae.database.domain.data.DomainData#getSubDomainMap} respectively.</p>
 *
 * @param <Property> the property enum type defining this sub-domain's persistable fields
 * @see SubDomainProperty
 * @see Domain
 * @see io.github.trae.database.domain.data.DomainData
 */
public interface SubDomain<Property extends Enum<?> & SubDomainProperty> {

    /**
     * Returns the unique identifier for this sub-domain instance.
     *
     * <p>Used as the map key when serialized within the parent document.</p>
     *
     * @return the sub-domain's UUID
     */
    UUID getId();

    /**
     * Returns the current value of the given property on this sub-domain.
     *
     * <p>Used by the repository's serialization layer to build the nested
     * data map for save and update operations.</p>
     *
     * @param property the property to read
     * @return the current value, or {@code null} if unset
     */
    Object getValueByProperty(final Property property);
}