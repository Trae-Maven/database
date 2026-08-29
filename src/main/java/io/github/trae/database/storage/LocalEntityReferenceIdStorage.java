package io.github.trae.database.storage;

import java.util.UUID;

/**
 * Local cache tier that maps a secondary key to an entity's identifier rather
 * than holding the entity itself.
 *
 * <p>An entity reachable by more than one key — an account by id, by email, by
 * username — is cached once under its identifier, and every other key points at
 * that one copy. Storing the entity in each storage instead would mean three
 * copies to keep in step on every write, and three that can drift apart the
 * moment one is refreshed and another is not.</p>
 *
 * <p>The cost is a second hop: resolve the key to an identifier here, then the
 * identifier to the entity through the primary storage. Both are map lookups
 * locally, and the identifier leg usually hits its own cache, so the indirection
 * is close to free.</p>
 *
 * <p>Subclasses supply only {@link #getKey(io.github.trae.database.entity.Entity)}
 * — the rest of the mapping is the same for every storage of this shape. That
 * key is derived from the entity, so a storage keyed on something the entity can
 * change needs {@link Storage#reIndex(Object, Object)} on update: the new key is
 * derivable, the old one is not.</p>
 *
 * @param <Key>    the secondary key entries are stored under
 * @param <Entity> the entity type the identifiers belong to
 */
public abstract class LocalEntityReferenceIdStorage<Key, Entity extends io.github.trae.database.entity.Entity> extends LocalStorage<Key, UUID, Entity> {

    /**
     * {@inheritDoc}
     *
     * <p>Stores the entity's identifier under the key derived from it.</p>
     */
    @Override
    public void index(final Entity entity) {
        this.put(this.getKey(entity), entity.getId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the mapping for the entity's current key. An entity whose key
     * has already changed no longer resolves to the entry it left behind, which
     * is what {@link Storage#reIndex(Object, Object)} exists to handle.</p>
     */
    @Override
    public void unIndex(final Entity entity) {
        this.remove(this.getKey(entity));
    }

    /**
     * Returns the key this storage files an entity under.
     *
     * @param entity the entity to derive a key from
     * @return the secondary key, or {@code null} if the entity has none
     */
    protected abstract Key getKey(final Entity entity);
}