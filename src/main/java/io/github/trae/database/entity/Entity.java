package io.github.trae.database.entity;

import java.util.UUID;

/**
 * Marker interface for every persistable domain object.
 *
 * <p>An entity is identified by a single {@link UUID}, stored in the {@code id}
 * column of its table. Implementations must also declare a constructor taking
 * that {@link UUID} — {@link io.github.trae.database.repository.EntityRepository}
 * resolves it reflectively and calls it when rebuilding an entity from a row,
 * then populates the remaining columns through the registered
 * {@link io.github.trae.database.entity.property.EntityProperty} setters.</p>
 *
 * @see io.github.trae.database.entity.property.EntityProperty
 * @see io.github.trae.database.repository.EntityRepository
 */
public interface Entity {

    /**
     * Returns this entity's unique identifier.
     *
     * <p>Used as the primary key, as the batch queue's coalescing key, and as the
     * match condition for every update and delete.</p>
     *
     * @return the entity's identifier, never {@code null} for a persisted entity
     */
    UUID getId();
}