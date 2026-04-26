package io.github.trae.database.domain.models;

/**
 * Marker interface for sub-domain property enums.
 *
 * <p>Each sub-domain model defines its own enum implementing this interface,
 * where each constant represents a persistable field on the sub-domain.
 * Extends {@link SharedDomainProperty} to allow reuse of
 * {@link io.github.trae.database.domain.data.DomainData} for both top-level
 * domains and embedded sub-domains.</p>
 *
 * @see SubDomain
 * @see SharedDomainProperty
 * @see io.github.trae.database.domain.data.DomainData
 */
public interface SubDomainProperty extends SharedDomainProperty {
}