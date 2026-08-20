package io.github.trae.database.entity;

import io.github.trae.database.lookup.LookupProvider;
import io.github.trae.database.repository.EntityRepository;
import io.github.trae.database.storage.LocalStorage;
import io.github.trae.database.storage.RedisStorage;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Implemented by a domain manager to expose cached, coalesced access to one
 * entity type.
 *
 * <p>A holder wires together the four pieces a lookup needs: the repository that
 * reaches the database, the local and Redis caches sitting in front of it, and
 * the {@link LookupProvider} that walks those tiers in order while collapsing
 * concurrent identical requests into one.</p>
 *
 * <p>The default methods cover lookup by identifier. Additional lookups — by
 * email, by name, by any other unique key — follow the same shape with their own
 * namespace, their own storage, and the matching repository method as the
 * database fallback.</p>
 *
 * @param <Entity>     the entity type held
 * @param <Repository> the repository type serving it
 * @see LookupProvider
 */
public interface EntityHolder<Entity extends io.github.trae.database.entity.Entity, Repository extends EntityRepository<Entity>> {

    /**
     * Returns the repository backing this holder.
     *
     * @return the entity's repository
     */
    Repository getRepository();

    /**
     * Returns the in-memory cache keyed by entity identifier.
     *
     * @return the local storage, or {@code null} to skip the local tier
     */
    LocalStorage<UUID, Entity> getIdLocalStorage();

    /**
     * Returns the Redis cache keyed by entity identifier.
     *
     * @return the Redis storage, or {@code null} to skip the Redis tier
     */
    RedisStorage<Entity> getIdRedisStorage();

    /**
     * Returns the lookup provider coordinating tiered reads for this entity.
     *
     * @return the lookup provider
     */
    LookupProvider<Entity> getLookupProvider();

    /**
     * Places an entity into every cache this holder maintains.
     *
     * <p>Called by the lookup provider whenever an entity is resolved from a
     * slower tier, so subsequent reads are served closer to the caller.</p>
     *
     * @param entity the entity to cache
     */
    void cacheEntity(final Entity entity);

    /**
     * Removes an entity from every cache this holder maintains.
     *
     * @param entity the entity to evict
     */
    void evictEntity(final Entity entity);

    /**
     * Resolves an entity by identifier, blocking until it is available.
     *
     * <p>Checks local storage, then Redis, then the database, caching whatever it
     * finds on the way back.</p>
     *
     * @param id the entity's identifier
     * @return the entity, or empty if no such entity exists
     */
    default Optional<Entity> getEntityByIdSynchronously(final UUID id) {
        return this.getLookupProvider().lookupEntitySynchronously(
                "id",
                id,
                this.getIdLocalStorage(),
                this.getIdRedisStorage(),
                this::cacheEntity,
                this.getRepository()::findById
        );
    }

    /**
     * Resolves an entity by identifier without blocking the calling thread.
     *
     * <p>Same tier order as {@link #getEntityByIdSynchronously(UUID)}, with the
     * work performed on the lookup provider's executor.</p>
     *
     * @param id the entity's identifier
     * @return a future completing with the entity, or with empty if no such
     * entity exists
     */
    default CompletableFuture<Optional<Entity>> getEntityByIdAsynchronously(final UUID id) {
        return this.getLookupProvider().lookupEntityAsynchronously(
                "id",
                id,
                this.getIdLocalStorage(),
                this.getIdRedisStorage(),
                this::cacheEntity,
                this.getRepository()::findById
        );
    }

    /**
     * Returns whether an entity with the given identifier exists anywhere.
     *
     * <p>Walks the same tiers as a lookup but stops at the first hit without
     * fetching or caching the entity, so a cached identifier costs a map lookup
     * and an uncached one costs an existence query rather than a full row
     * read.</p>
     *
     * @param id the identifier to check
     * @return {@code true} if the entity exists in any tier
     */
    default boolean isEntityById(final UUID id) {
        return id != null && (this.getIdLocalStorage().contains(id) || this.getIdRedisStorage().contains(id.toString()) || this.getRepository().exists(id));
    }
}