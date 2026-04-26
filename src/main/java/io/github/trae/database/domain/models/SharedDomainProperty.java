package io.github.trae.database.domain.models;

/**
 * Common marker interface for all property enums used in domain mapping.
 *
 * <p>Serves as the shared upper bound for both {@link DomainProperty} and
 * {@link SubDomainProperty}, allowing {@link io.github.trae.database.domain.data.DomainData}
 * to operate on either type through a single generic constraint.</p>
 *
 * @see DomainProperty
 * @see SubDomainProperty
 * @see io.github.trae.database.domain.data.DomainData
 */
public interface SharedDomainProperty {
}