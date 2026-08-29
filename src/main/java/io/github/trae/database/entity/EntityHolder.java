package io.github.trae.database.entity;

import io.github.trae.database.lookup.LookupProvider;
import io.github.trae.database.repository.EntityRepository;
import io.github.trae.database.storage.LocalEntityReferenceIdStorage;
import io.github.trae.database.storage.LocalStorage;
import io.github.trae.database.storage.RedisEntityReferenceIdStorage;
import io.github.trae.database.storage.RedisStorage;
import io.github.trae.utilities.objects.function.Function;

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
 * <p>Lookups come in two shapes. By identifier, the tier walk resolves straight
 * to the entity. By anything else — email, username — the walk resolves to an
 * identifier and that identifier resolves to the entity, so one cached copy
 * serves every path to it. Both legs are covered by
 * {@link #getEntityByKeyAsynchronously}: a manager supplies the namespace, the
 * two reference storages and the repository fallback, and gets the entity
 * back.</p>
 *
 * <p>Every default method routes through the provider rather than touching a
 * storage directly, so none of them blocks the calling thread on Redis or the
 * database, none of them trips over a null tier, and all of them share one
 * in-flight lookup per key.</p>
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
    LocalStorage<UUID, Entity, Entity> getIdLocalStorage();

    /**
     * Returns the Redis cache keyed by entity identifier.
     *
     * @return the Redis storage, or {@code null} to skip the Redis tier
     */
    RedisStorage<Entity, Entity> getIdRedisStorage();

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
     * slower tier, so subsequent reads are served closer to the caller. Runs on
     * the provider's executor, never on the thread that asked for the entity.</p>
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
     * Resolves an entity by a secondary key, blocking until it is available.
     *
     * <p>Two legs — the key resolves to an identifier through the reference
     * storages, then the identifier resolves to the entity. A manager wraps this
     * once per key it supports rather than composing the legs at every call
     * site.</p>
     *
     * @param <Key>                      the lookup key type
     * @param namespace                  distinguishes this lookup from others on
     *                                   the same entity
     * @param key                        the value to look up by
     * @param localStorage               the local reference tier, or {@code null}
     *                                   to skip it
     * @param redisStorage               the Redis reference tier, or {@code null}
     *                                   to skip it
     * @param databaseIdOptionalFunction the database fallback for the key leg
     * @return the entity, or empty if no such entity exists
     */
    default <Key> Optional<Entity> getEntityByKeySynchronously(final String namespace, final Key key, final LocalEntityReferenceIdStorage<Key, Entity> localStorage, final RedisEntityReferenceIdStorage<Entity> redisStorage, final Function<Key, Optional<UUID>> databaseIdOptionalFunction) {
        return this.getLookupProvider()
                .lookupIdSynchronously(namespace, key, localStorage, redisStorage, databaseIdOptionalFunction)
                .flatMap(this::getEntityByIdSynchronously);
    }

    /**
     * Resolves an entity by a secondary key without blocking the calling thread.
     *
     * <p>Same two legs as {@link #getEntityByKeySynchronously}, each coalescing
     * on its own — a hundred callers asking by the same key produce one key
     * lookup and one identifier lookup between them.</p>
     *
     * @param <Key>                      the lookup key type
     * @param namespace                  distinguishes this lookup from others on
     *                                   the same entity
     * @param key                        the value to look up by
     * @param localStorage               the local reference tier, or {@code null}
     *                                   to skip it
     * @param redisStorage               the Redis reference tier, or {@code null}
     *                                   to skip it
     * @param databaseIdOptionalFunction the database fallback for the key leg
     * @return a future completing with the entity, or with empty if no such
     * entity exists
     */
    default <Key> CompletableFuture<Optional<Entity>> getEntityByKeyAsynchronously(final String namespace, final Key key, final LocalEntityReferenceIdStorage<Key, Entity> localStorage, final RedisEntityReferenceIdStorage<Entity> redisStorage, final Function<Key, Optional<UUID>> databaseIdOptionalFunction) {
        return this.getLookupProvider()
                .lookupIdAsynchronously(namespace, key, localStorage, redisStorage, databaseIdOptionalFunction)
                .thenCompose(idOptional -> idOptional.map(this::getEntityByIdAsynchronously).orElseGet(() -> CompletableFuture.completedFuture(Optional.empty())));
    }
}