package io.github.trae.database.entity;

import io.github.trae.database.constants.Constants;
import io.github.trae.database.entity.property.EntityProperty;
import io.github.trae.database.entity.update.EntityUpdateDto;
import io.github.trae.database.lookup.LookupProvider;
import io.github.trae.database.repository.EntityRepository;
import io.github.trae.database.storage.LocalEntityReferenceIdStorage;
import io.github.trae.database.storage.RedisEntityReferenceIdStorage;
import io.github.trae.database.storage.types.IdLocalStorage;
import io.github.trae.database.storage.types.IdRedisStorage;
import io.github.trae.utilities.UtilGeneric;
import io.github.trae.utilities.UtilJava;
import io.github.trae.utilities.objects.function.Function;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * <p>Writes go through {@link #updateEntity}, which keeps this instance's caches,
 * the database and every other instance in step. Redis is the source of truth
 * between instances: the writer refreshes it, the others drop what they were
 * holding and read it back on their next lookup.</p>
 *
 * @param <Entity>     the entity type held
 * @param <Repository> the repository type serving it
 * @see LookupProvider
 */
public interface EntityHolder<Entity extends io.github.trae.database.entity.Entity, Repository extends EntityRepository<Entity>> {

    /**
     * Identifies this JVM on the update channel, so an instance ignores the
     * messages it published itself rather than evicting what it just cached.
     */
    UUID INSTANCE_ID = UUID.randomUUID();

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
    IdLocalStorage<Entity> getIdLocalStorage();

    /**
     * Returns the Redis cache keyed by entity identifier.
     *
     * @return the Redis storage, or {@code null} to skip the Redis tier
     */
    IdRedisStorage<Entity> getIdRedisStorage();

    /**
     * Returns the lookup provider coordinating tiered reads for this entity.
     *
     * @return the lookup provider
     */
    LookupProvider<Entity> getLookupProvider();

    /**
     * The entity type held, read off this holder's own type arguments.
     *
     * <p>Names the update channel and resolves column names arriving on it.
     * Requires the implementing class to state its entity type concretely; a
     * holder that is itself generic has nothing to read and should override
     * this.</p>
     *
     * @return the entity class
     */
    @SuppressWarnings("unchecked")
    default Class<Entity> getEntityType() {
        return (Class<Entity>) UtilGeneric.getGenericParameter(this.getClass(), EntityHolder.class, 0);
    }

    /**
     * Indexes the entity in every local tier.
     *
     * <p>The identifier tier plus every local reference tier, under the entity's
     * current values. Called on its own when reacting to another instance's
     * change, where the shared copy is already correct.</p>
     *
     * @param entity the entity to cache
     */
    void cacheLocalEntity(final Entity entity);

    /**
     * Indexes the entity in every Redis tier.
     *
     * <p>The shared half of the same job. Only the instance making a change calls
     * this: a receiver writing the same keys back would undo the writer's work and
     * race a second update landing behind the first.</p>
     *
     * @param entity the entity to cache
     */
    void cacheRedisEntity(final Entity entity);

    /**
     * Drops the entity from every local tier.
     *
     * <p>Used when this instance's copy is stale and there is nothing to replace
     * it with, leaving the next lookup to walk the tiers again.</p>
     *
     * @param entity the entity to evict
     */
    void evictLocalEntity(final Entity entity);

    /**
     * Drops the entity from every Redis tier.
     *
     * <p>Removes the copy every instance reads from, so this is for an entity
     * that is genuinely gone rather than one that merely changed.</p>
     *
     * @param entity the entity to evict
     */
    void evictRedisEntity(final Entity entity);

    /**
     * Pins the entity in every local tier maintained by this holder.
     *
     * <p>A pinned entry remains resident while the entity is actively owned by this
     * instance: it cannot expire, be ordinarily evicted, or be removed to satisfy a
     * storage size limit. It may still be refreshed or replaced, with the pinned
     * state carried onto the new cache entry.</p>
     *
     * <p>Implementations should pin the identifier entry and every local reference
     * entry currently indexing the entity.</p>
     *
     * @param entity the entity to pin locally
     */
    void pinLocalEntity(final Entity entity);

    /**
     * Unpins the entity from every local tier maintained by this holder.
     *
     * <p>Unpinning does not remove the entity. Each affected entry returns to its
     * storage's normal cache policy and begins a fresh time-to-live from the moment
     * it is unpinned.</p>
     *
     * <p>Implementations should unpin the identifier entry and every local reference
     * entry currently indexing the entity.</p>
     *
     * @param entity the entity to unpin locally
     */
    void unpinLocalEntity(final Entity entity);

    /**
     * Writes the entity into every tier this holder maintains.
     *
     * <p>Called after a lookup finds an entity further down the tiers, and after
     * {@link #updateEntity} mutates one. An implementation indexes the entity by
     * identifier in both the local and Redis tiers, and re-indexes every
     * reference tier under the entity's current values.</p>
     *
     * @param entity the entity to cache
     */
    default void cacheEntity(final Entity entity) {
        this.cacheLocalEntity(entity);
        this.cacheRedisEntity(entity);
    }

    /**
     * Drops the entity from every tier this holder maintains.
     *
     * <p>The full eviction, for an entity that is gone or a deliberate flush. The
     * update path uses {@link #evictLocalEntity} instead, since the shared copy is
     * what the other instances read the new state from.</p>
     *
     * @param entity the entity to evict
     */
    default void evictEntity(final Entity entity) {
        this.evictLocalEntity(entity);
        this.evictRedisEntity(entity);
    }

    /**
     * Removes the local reference entries a single property indexes the entity
     * under.
     *
     * <p>Called with the entity still holding the value the entry was built from,
     * so an implementation can compute the key to remove straight off it. A
     * property that indexes nothing does nothing here.</p>
     *
     * @param entity   the entity whose entries are going stale
     * @param property the property whose entries to remove
     */
    void deleteStaleLocalStorage(final Entity entity, final EntityProperty<? super Entity, ?> property);

    /**
     * Removes the Redis reference entries a single property indexes the entity
     * under.
     *
     * <p>The shared half of the same job, with the same requirement that the
     * entity still holds the old value. Only the instance making a change calls
     * this; a receiver would be deleting keys the writer has already rebuilt.</p>
     *
     * @param entity   the entity whose entries are going stale
     * @param property the property whose entries to remove
     */
    void deleteStaleRedisStorage(final Entity entity, final EntityProperty<? super Entity, ?> property);

    /**
     * The channel this entity's updates are published on.
     *
     * <p>Named from the entity type rather than the holder, so every instance
     * agrees on it regardless of what its manager is called.</p>
     *
     * @return the channel name
     */
    default String getUpdateChannel() {
        return "Entity-Update-%s".formatted(this.getEntityType().getSimpleName());
    }

    /**
     * Applies a change to an entity and propagates it everywhere.
     *
     * <p>Each declared property's value is read before the mutation and compared
     * after, and only the ones that actually moved are acted on. A caller
     * declaring more than it ends up changing, or writing the same value back,
     * costs nothing beyond the comparison.</p>
     *
     * <p>The stale reference entries are dropped first, while the entity still
     * holds the old values the indexes were built from. Only then does the
     * mutation run, and the re-cache re-indexes under the new ones. Re-cached
     * reference entries are re-pinned when the entity was already pinned locally.</p>
     *
     * <p>Any persistent changes are written to the database, then all changes are
     * broadcast. Redis already holds the new copy by then, so an instance reacting
     * to the message reads the new state rather than racing the writer.</p>
     *
     * <p>Only the changed properties are broadcast, including non-persistent ones,
     * since a property cannot be serialised: it holds a getter, a setter and a
     * jOOQ type. The receiving side resolves each column name back through the
     * registry.</p>
     *
     * @param entity             the entity being changed
     * @param entityPropertyList the properties the mutation may touch
     * @param updateRunnable     applies the change to the entity
     */
    default void updateEntity(final Entity entity, final List<EntityProperty<? super Entity, ?>> entityPropertyList, final Runnable updateRunnable) {
        final Map<EntityProperty<? super Entity, ?>, Object> previousValueMap = UtilJava.createMap(new HashMap<>(), map -> {
            for (final EntityProperty<? super Entity, ?> entityProperty : entityPropertyList) {
                map.put(entityProperty, snapshotValue(entityProperty.getValue(entity)));
            }
        });

        final IdLocalStorage<Entity> idLocalStorage = this.getIdLocalStorage();
        final boolean pinned = idLocalStorage != null && idLocalStorage.isPinned(entity.getId());

        for (final EntityProperty<? super Entity, ?> entityProperty : entityPropertyList) {
            this.deleteStaleLocalStorage(entity, entityProperty);
            this.deleteStaleRedisStorage(entity, entityProperty);
        }

        updateRunnable.run();

        final List<EntityProperty<? super Entity, ?>> changedPropertyList = entityPropertyList.stream()
                .filter(entityProperty -> !Objects.equals(previousValueMap.get(entityProperty), entityProperty.getValue(entity)))
                .toList();

        this.cacheEntity(entity);

        if (pinned) {
            this.pinLocalEntity(entity);
        }

        if (changedPropertyList.isEmpty()) {
            return;
        }

        this.getRepository().update(entity, changedPropertyList);

        if (this.getIdRedisStorage() != null) {
            this.getIdRedisStorage().getRedisDriver().publish(this.getUpdateChannel(), Constants.GSON.toJson(new EntityUpdateDto(INSTANCE_ID, entity.getId(), changedPropertyList.stream().map(EntityProperty::getColumn).toList())));
        }
    }

    /**
     * Convenience form of {@link #updateEntity(io.github.trae.database.entity.Entity, List, Runnable)}
     * for a change touching a single property.
     *
     * @param entity         the entity being changed
     * @param entityProperty the property the mutation touches
     * @param updateRunnable applies the change to the entity
     */
    default void updateEntity(final Entity entity, final EntityProperty<? super Entity, ?> entityProperty, final Runnable updateRunnable) {
        this.updateEntity(entity, Collections.singletonList(entityProperty), updateRunnable);
    }

    /**
     * Creates a snapshot of the specified value for change detection.
     *
     * <p>Maps, sets and collections are copied so mutations to the original
     * value do not affect the snapshot. Other values are returned unchanged.</p>
     *
     * @param value the value to snapshot
     * @return the snapshot value
     */
    private static Object snapshotValue(final Object value) {
        if (value != null) {
            if (value instanceof final Map<?, ?> map) {
                return Map.copyOf(map);
            }

            if (value instanceof final Set<?> set) {
                return Set.copyOf(set);
            }

            if (value instanceof final Collection<?> collection) {
                return List.copyOf(collection);
            }
        }

        return value;
    }

    /**
     * Subscribes this instance to other instances' updates.
     *
     * <p>An instance only reacts to an entity it was already holding: if it never
     * cached that identifier, there is nothing stale to drop and nothing to warm.
     * When it was, the changed columns' reference entries go first, using the
     * stale copy's own values, and the writer's copy is then read back out of
     * Redis and indexed over the top, so nothing has to be evicted and looked up
     * again.</p>
     *
     * <p>If the entity was pinned locally, that state is restored after the updated
     * entity is cached so reference entries created under new keys remain pinned.</p>
     *
     * <p>Redis coming back empty means the entity is gone or its entry lapsed,
     * with nothing to replace the stale copy with, so that copy is dropped
     * instead.</p>
     *
     * <p>Only the local tiers are touched. The writer already refreshed Redis,
     * and every receiver writing the same keys back would undo that work and race
     * a second update landing behind the first.</p>
     *
     * <p>A column that resolves to no registered property is skipped rather than
     * failing the whole message, which covers an instance running a build that
     * does not know about it yet.</p>
     *
     * <p>Does nothing without a Redis tier, since there are no other instances to
     * hear from.</p>
     */
    default void listenForEntityUpdates() {
        final IdRedisStorage<Entity> idRedisStorage = this.getIdRedisStorage();
        if (idRedisStorage == null) {
            return;
        }

        idRedisStorage.getRedisDriver().subscribe(this.getUpdateChannel(), message -> {
            final EntityUpdateDto entityUpdateDto = Constants.GSON.fromJson(message, EntityUpdateDto.class);
            if (entityUpdateDto == null || INSTANCE_ID.equals(entityUpdateDto.getInstanceId())) {
                return;
            }

            final IdLocalStorage<Entity> idLocalStorage = this.getIdLocalStorage();
            if (idLocalStorage == null) {
                return;
            }

            idLocalStorage.get(entityUpdateDto.getId()).ifPresent(entity -> {
                final boolean pinned = idLocalStorage.isPinned(entity.getId());

                for (final String column : entityUpdateDto.getColumnList()) {
                    final EntityProperty<? super Entity, ?> entityProperty = EntityProperty.getEntityPropertyByColumn(this.getEntityType(), column);
                    if (entityProperty == null) {
                        continue;
                    }

                    this.deleteStaleLocalStorage(entity, entityProperty);
                }

                idRedisStorage.get(entityUpdateDto.getId().toString()).ifPresentOrElse(updatedEntity -> {
                    this.cacheLocalEntity(updatedEntity);

                    if (pinned) {
                        this.pinLocalEntity(updatedEntity);
                    }
                }, () -> this.evictLocalEntity(entity));
            });
        });
    }

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