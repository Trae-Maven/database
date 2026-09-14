package io.github.trae.database.storage.types;

import io.github.trae.database.storage.LocalStorage;

import java.util.UUID;

/**
 * The in-memory tier holding entities under their own identifier.
 *
 * <p>The primary local cache: every other local tier resolves to an identifier
 * and then to here, so one copy serves every path to an entity. Keying is fixed
 * rather than overridable, since an entity's identifier is the one thing about
 * it that never changes.</p>
 *
 * <p>A subclass supplies only the TTL.</p>
 *
 * @param <Entity> the entity type held
 */
public abstract class IdLocalStorage<Entity extends io.github.trae.database.entity.Entity> extends LocalStorage<UUID, Entity, Entity> {

    /**
     * Stores the entity under its identifier.
     *
     * @param entity the entity to cache
     */
    @Override
    public final void index(final Entity entity) {
        this.put(entity.getId(), entity);
    }

    /**
     * Drops the entity's cached copy.
     *
     * @param entity the entity to evict
     */
    @Override
    public final void unIndex(final Entity entity) {
        this.remove(entity.getId());
    }
}