package io.github.trae.database.domain.models;

/**
 * Marker interface for domain property enums.
 *
 * <p>Each domain model defines its own enum implementing this interface,
 * where each constant represents a persistable field on the domain.
 * Used by {@link Domain#getValueByProperty} to serialize domain state
 * and by {@link io.github.trae.database.domain.data.DomainData} to
 * deserialize raw database results back into typed fields.</p>
 *
 * @see Domain
 * @see io.github.trae.database.domain.data.DomainData
 */
public interface DomainProperty {
}