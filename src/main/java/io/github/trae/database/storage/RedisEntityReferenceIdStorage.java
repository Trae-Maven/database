package io.github.trae.database.storage;

import io.github.trae.database.driver.RedisDriver;

import java.util.UUID;

/**
 * Redis counterpart to {@link LocalEntityReferenceIdStorage} — maps a secondary
 * key to an entity's identifier, shared across every process pointing at the
 * same Redis.
 *
 * <p>Holds the same one-copy-per-entity discipline across the network that the
 * local tier holds in one process: whichever server writes the entity writes it
 * once under its identifier, and every key pointing at it stays a pointer. A
 * server resolving an email gets an identifier from here and the entity from the
 * identifier storage, so the two servers never disagree about what the entity
 * looks like.</p>
 *
 * <p>Identifiers are stored as their canonical string form, which is fixed-width
 * and case-stable, so a malformed value can only come from something outside
 * this class having written the key. That is treated as a corrupt entry and
 * evicted, the same as any other value that will not decode.</p>
 *
 * <p>The key is always a string here, since that is what Redis keys on.</p>
 *
 * <p>Subclasses supply only {@link #getKey(io.github.trae.database.entity.Entity)}.
 * That key is derived from the entity, so a storage keyed on something the
 * entity can change needs {@link Storage#reIndex(Object, Object)} on update: the
 * new key is derivable, the old one is not.</p>
 *
 * @param <Entity> the entity type the identifiers belong to
 */
public abstract class RedisEntityReferenceIdStorage<Entity extends io.github.trae.database.entity.Entity> extends RedisStorage<UUID, Entity> {

    /**
     * Creates a storage over the given connection and namespace.
     *
     * @param redisDriver the connection used for every command
     * @param namespace   the prefix applied to every key this storage owns
     */
    protected RedisEntityReferenceIdStorage(final RedisDriver redisDriver, final String namespace) {
        super(redisDriver, namespace);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the entity's identifier under the key derived from it. An entity
     * with no key is ignored, since {@link Storage#put(Object, Object)} treats a
     * null key as a no-op.</p>
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
     * {@inheritDoc}
     */
    @Override
    protected String serialize(final UUID value) {
        return value.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Throws on a value that is not a well-formed identifier, which the read
     * path turns into a miss and evicts rather than passing to the caller.</p>
     */
    @Override
    protected UUID deserialize(final String value) {
        return UUID.fromString(value);
    }

    /**
     * Returns the key this storage files an entity under.
     *
     * @param entity the entity to derive a key from
     * @return the secondary key, or {@code null} if the entity has none
     */
    protected abstract String getKey(final Entity entity);
}