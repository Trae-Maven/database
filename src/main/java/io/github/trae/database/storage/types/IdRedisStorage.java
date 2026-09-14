package io.github.trae.database.storage.types;

import io.github.trae.database.constants.Constants;
import io.github.trae.database.driver.RedisDriver;
import io.github.trae.database.storage.RedisStorage;

import java.util.Locale;

/**
 * The Redis tier holding entities under their own identifier.
 *
 * <p>The shared copy every instance reads back from, and the source of truth
 * between them: a writer refreshes this, the others drop their local copies and
 * find the new state here on their next lookup. Namespaced from the entity's
 * simple name, so every instance agrees on the key without being told it.</p>
 *
 * <p>Entities are stored as JSON through {@link Constants#GSON}, so anything held
 * on one needs to survive a default Gson round trip. A subclass supplies only
 * the TTL.</p>
 *
 * @param <Entity> the entity type held
 */
public abstract class IdRedisStorage<Entity extends io.github.trae.database.entity.Entity> extends RedisStorage<Entity, Entity> {

    /**
     * The entity class, kept for deserialisation and used to build the key
     * namespace.
     */
    private final Class<Entity> type;

    /**
     * @param redisDriver the connection this tier reads and writes through
     * @param type        the entity class held
     */
    public IdRedisStorage(final RedisDriver redisDriver, final Class<Entity> type) {
        super(redisDriver, "%s:id".formatted(type.getSimpleName().toLowerCase(Locale.ROOT)));

        this.type = type;
    }

    /**
     * Encodes the entity as JSON.
     *
     * @param entity the entity to store
     * @return the stored form
     */
    @Override
    protected final String serialize(final Entity entity) {
        return Constants.GSON.toJson(entity);
    }

    /**
     * Rebuilds an entity from its stored JSON.
     *
     * @param value the stored form
     * @return the reconstructed entity
     */
    @Override
    protected final Entity deserialize(final String value) {
        return Constants.GSON.fromJson(value, this.type);
    }

    /**
     * Stores the entity under its identifier.
     *
     * @param entity the entity to cache
     */
    @Override
    public final void index(final Entity entity) {
        this.put(entity.getId().toString(), entity);
    }

    /**
     * Drops the entity's shared copy.
     *
     * @param entity the entity to evict
     */
    @Override
    public final void unIndex(final Entity entity) {
        this.remove(entity.getId().toString());
    }
}