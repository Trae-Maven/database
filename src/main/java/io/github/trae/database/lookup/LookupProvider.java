package io.github.trae.database.lookup;

import io.github.trae.database.entity.EntityHolder;
import io.github.trae.database.repository.EntityRepository;
import io.github.trae.database.storage.LocalStorage;
import io.github.trae.database.storage.RedisStorage;
import io.github.trae.utilities.UtilString;
import io.github.trae.utilities.objects.consumer.Consumer;
import io.github.trae.utilities.objects.function.Function;
import lombok.AllArgsConstructor;
import org.jooq.Condition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Resolves entities through the cache tiers in order, collapsing concurrent
 * identical lookups into a single piece of work.
 *
 * <p>Two problems are solved here. The first is tier order: a lookup tries local
 * storage, then Redis, then the database, caching whatever it finds on the way
 * back so the next caller is served closer. The second is the stampede — a
 * hundred callers asking for the same uncached entity at once would otherwise
 * produce a hundred identical queries. The first caller registers a future under
 * the lookup's key and does the work; the rest find that future and wait on
 * it.</p>
 *
 * <p>Every method exists in both forms. The asynchronous one returns immediately
 * with a future, and the synchronous one blocks on that same future — so both
 * kinds of caller share one in-flight lookup rather than duplicating it. Work
 * runs on virtual threads, which suits blocking JDBC and Redis calls and means a
 * synchronous caller waiting inside a lookup cannot starve a fixed pool.</p>
 *
 * <p>Keys are namespaced, so an identifier lookup and an email lookup for the
 * same entity never collide, and string keys are uppercased so callers differing
 * only in casing still share one lookup.</p>
 *
 * @param <Entity> the entity type this provider resolves
 * @see EntityHolder
 */
@AllArgsConstructor
public class LookupProvider<Entity extends io.github.trae.database.entity.Entity> {

    /**
     * Runs every lookup supplier. Shared across all providers — a virtual thread
     * executor holds no threads at rest, so one serves the whole application.
     */
    private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * In-flight single-entity lookups, by namespace then key.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableFuture<Optional<Entity>>>> singleInFlightMap = new ConcurrentHashMap<>();

    /**
     * In-flight multi-entity lookups, by namespace then key.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableFuture<List<Entity>>>> listInFlightMap = new ConcurrentHashMap<>();

    /**
     * The holder supplying the repository for database fallbacks.
     */
    private final EntityHolder<Entity, ?> entityHolder;

    /**
     * Resolves an entity through the tiers, blocking until it is available.
     *
     * @param <Key>                          the lookup key type
     * @param namespace                      distinguishes this lookup from others
     *                                       on the same entity
     * @param key                            the value to look up by
     * @param localStorage                   the local tier, or {@code null} to
     *                                       skip it
     * @param redisStorage                   the Redis tier, or {@code null} to
     *                                       skip it
     * @param storageAddConsumer             caches an entity found in a slower
     *                                       tier
     * @param databaseEntityOptionalFunction the database fallback
     * @return the entity, or empty if no tier holds it
     */
    public <Key> Optional<Entity> lookupEntitySynchronously(final String namespace, final Key key, final LocalStorage<Key, Entity> localStorage, final RedisStorage<Entity> redisStorage, final Consumer<Entity> storageAddConsumer, final Function<Key, Optional<Entity>> databaseEntityOptionalFunction) {
        return this.joinSynchronously(this.lookupEntityAsynchronously(namespace, key, localStorage, redisStorage, storageAddConsumer, databaseEntityOptionalFunction));
    }

    /**
     * Resolves an entity through the tiers without blocking the caller.
     *
     * <p>An invalid namespace or key completes immediately with empty rather than
     * scheduling work.</p>
     *
     * @param <Key>                          the lookup key type
     * @param namespace                      distinguishes this lookup from others
     *                                       on the same entity
     * @param key                            the value to look up by
     * @param localStorage                   the local tier, or {@code null} to
     *                                       skip it
     * @param redisStorage                   the Redis tier, or {@code null} to
     *                                       skip it
     * @param storageAddConsumer             caches an entity found in a slower
     *                                       tier
     * @param databaseEntityOptionalFunction the database fallback
     * @return a future completing with the entity, or with empty
     */
    public <Key> CompletableFuture<Optional<Entity>> lookupEntityAsynchronously(final String namespace, final Key key, final LocalStorage<Key, Entity> localStorage, final RedisStorage<Entity> redisStorage, final Consumer<Entity> storageAddConsumer, final Function<Key, Optional<Entity>> databaseEntityOptionalFunction) {
        if (UtilString.isEmpty(namespace) || (key == null || key instanceof final String keyString && UtilString.isEmpty(keyString))) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final String cleanKey = key instanceof final String keyString ? keyString.toUpperCase(Locale.ROOT) : key.toString();

        return this.singleAsynchronously(namespace, cleanKey, () -> {
            // Local check
            if (localStorage != null) {
                final Optional<Entity> localEntityOptional = localStorage.get(key);
                if (localEntityOptional.isPresent()) {
                    return localEntityOptional;
                }
            }

            // Redis check
            if (redisStorage != null) {
                final Optional<Entity> redisEntityOptional = redisStorage.get(cleanKey);
                if (redisEntityOptional.isPresent()) {
                    if (storageAddConsumer != null) {
                        storageAddConsumer.accept(redisEntityOptional.get());
                    }

                    return redisEntityOptional;
                }
            }

            // Database check
            if (databaseEntityOptionalFunction != null) {
                final Optional<Entity> databaseEntityOptional = databaseEntityOptionalFunction.apply(key);

                databaseEntityOptional.ifPresent(databaseEntity -> {
                    if (storageAddConsumer != null) {
                        storageAddConsumer.accept(databaseEntity);
                    }
                });

                return databaseEntityOptional;
            }

            return Optional.empty();
        });
    }

    /**
     * Resolves every entity matching a filter across all tiers, blocking until
     * complete.
     *
     * @param namespace          distinguishes this lookup from others on the same
     *                           entity
     * @param predicate          filters the cached entities
     * @param condition          the equivalent condition for the database query
     * @param idLocalStorage     the local tier, or {@code null} to skip it
     * @param idRedisStorage     the Redis tier, or {@code null} to skip it
     * @param storageAddConsumer caches entities found in the database
     * @return the matching entities, deduplicated by identifier
     */
    public List<Entity> lookupAllValuesSynchronously(final String namespace, final Predicate<Entity> predicate, final Condition condition, final LocalStorage<UUID, Entity> idLocalStorage, final RedisStorage<Entity> idRedisStorage, final Consumer<Entity> storageAddConsumer) {
        return this.joinSynchronously(this.lookupAllValuesAsynchronously(namespace, predicate, condition, idLocalStorage, idRedisStorage, storageAddConsumer));
    }

    /**
     * Resolves every entity matching a filter across all tiers, without blocking
     * the caller.
     *
     * <p>The predicate and the condition must express the same rule — one is
     * applied in memory to the cached entities, the other in SQL. Identifiers
     * already found in a cache are excluded from the query, so the database only
     * returns what the caches missed.</p>
     *
     * @param namespace          distinguishes this lookup from others on the same
     *                           entity
     * @param predicate          filters the cached entities
     * @param condition          the equivalent condition for the database query
     * @param idLocalStorage     the local tier, or {@code null} to skip it
     * @param idRedisStorage     the Redis tier, or {@code null} to skip it
     * @param storageAddConsumer caches entities found in the database
     * @return a future completing with the matching entities, deduplicated by
     * identifier
     */
    public CompletableFuture<List<Entity>> lookupAllValuesAsynchronously(final String namespace, final Predicate<Entity> predicate, final Condition condition, final LocalStorage<UUID, Entity> idLocalStorage, final RedisStorage<Entity> idRedisStorage, final Consumer<Entity> storageAddConsumer) {
        if (UtilString.isEmpty(namespace) || predicate == null || condition == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return this.listAsynchronously(namespace, condition.toString(), () -> {
            // Local scrape
            final List<Entity> localEntityList = idLocalStorage != null ? idLocalStorage.values().stream().filter(predicate).toList() : Collections.emptyList();
            final Set<UUID> localEntityIdSet = localEntityList.stream().map(io.github.trae.database.entity.Entity::getId).collect(Collectors.toSet());

            // Redis scrape
            final List<Entity> redisEntityList = idRedisStorage != null ? idRedisStorage.values().stream().filter(predicate).toList() : Collections.emptyList();
            final Set<UUID> redisEntityIdSet = redisEntityList.stream().map(io.github.trae.database.entity.Entity::getId).collect(Collectors.toSet());

            // Database scrape
            final List<Entity> databaseEntityList = this.entityHolder.getRepository().findMany(localEntityIdSet.isEmpty() && redisEntityIdSet.isEmpty() ? condition : condition.and(EntityRepository.IDENTIFIER_FIELD.notIn(localEntityIdSet)).and(EntityRepository.IDENTIFIER_FIELD.notIn(redisEntityIdSet)));

            databaseEntityList.forEach(databaseEntity -> {
                if (storageAddConsumer != null) {
                    storageAddConsumer.accept(databaseEntity);
                }
            });

            return List.copyOf(Stream.of(localEntityList, redisEntityList, databaseEntityList).flatMap(List::stream).collect(Collectors.toMap(io.github.trae.database.entity.Entity::getId, entity -> entity, (first, second) -> first, LinkedHashMap::new)).values());
        });
    }

    /**
     * Runs a single-entity supplier under stampede protection.
     *
     * <p>Exposed so a holder can add its own lookups without reimplementing the
     * coalescing.</p>
     *
     * @param namespace the lookup namespace
     * @param key       the lookup key, already normalised
     * @param supplier  performs the lookup on a cache miss
     * @return a future completing with the supplier's result
     */
    public CompletableFuture<Optional<Entity>> singleAsynchronously(final String namespace, final String key, final Supplier<Optional<Entity>> supplier) {
        return this.coalesceAsynchronously(this.singleInFlightMap, namespace, key, supplier);
    }

    /**
     * Runs a multi-entity supplier under stampede protection.
     *
     * @param namespace the lookup namespace
     * @param key       the lookup key, already normalised
     * @param supplier  performs the lookup on a cache miss
     * @return a future completing with the supplier's result
     */
    public CompletableFuture<List<Entity>> listAsynchronously(final String namespace, final String key, final Supplier<List<Entity>> supplier) {
        return this.coalesceAsynchronously(this.listInFlightMap, namespace, key, supplier);
    }

    /**
     * Registers a lookup as in flight and runs it, or joins one already running.
     *
     * <p>The claim is made inside a {@code compute} on the outer map so the
     * namespace's map cannot be removed by a concurrent release while a key is
     * being registered into it — that race would let two threads each believe
     * they were first and run the supplier twice.</p>
     *
     * <p>Callers receive a copy of the shared future rather than the future
     * itself, so cancelling or completing the returned handle cannot affect
     * anyone else waiting on the same lookup.</p>
     *
     * @param <T>         the lookup's result type
     * @param inFlightMap the map tracking lookups of this shape
     * @param namespace   the lookup namespace
     * @param key         the lookup key
     * @param supplier    performs the lookup
     * @return a future completing with the result
     */
    private <T> CompletableFuture<T> coalesceAsynchronously(final ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableFuture<T>>> inFlightMap, final String namespace, final String key, final Supplier<T> supplier) {
        final CompletableFuture<T> completableFuture = new CompletableFuture<>();
        final AtomicReference<CompletableFuture<T>> existingReference = new AtomicReference<>();

        inFlightMap.compute(namespace, (__, namespaceMap) -> {
            final ConcurrentHashMap<String, CompletableFuture<T>> resolvedMap = namespaceMap == null ? new ConcurrentHashMap<>() : namespaceMap;

            existingReference.set(resolvedMap.putIfAbsent(key, completableFuture));

            return resolvedMap;
        });

        final CompletableFuture<T> existingCompletableFuture = existingReference.get();
        if (existingCompletableFuture != null) {
            return existingCompletableFuture.copy();
        }

        CompletableFuture.supplyAsync(supplier, EXECUTOR).whenComplete((value, throwable) -> {
            this.release(inFlightMap, namespace, key, completableFuture);

            if (throwable != null) {
                completableFuture.completeExceptionally(throwable);
            } else {
                completableFuture.complete(value);
            }
        });

        return completableFuture.copy();
    }

    /**
     * Removes a completed lookup from the in-flight map, dropping the namespace
     * once it holds nothing.
     *
     * <p>Removal is conditional on identity, so a lookup that has already been
     * replaced by a newer one for the same key is left alone.</p>
     *
     * @param <T>               the lookup's result type
     * @param inFlightMap       the map tracking lookups of this shape
     * @param namespace         the lookup namespace
     * @param key               the lookup key
     * @param completableFuture the future being retired
     */
    private <T> void release(final ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableFuture<T>>> inFlightMap, final String namespace, final String key, final CompletableFuture<T> completableFuture) {
        inFlightMap.compute(namespace, (__, namespaceMap) -> {
            if (namespaceMap == null) {
                return null;
            }

            namespaceMap.remove(key, completableFuture);

            return namespaceMap.isEmpty() ? null : namespaceMap;
        });
    }

    /**
     * Waits on a future, unwrapping the cause of a failure.
     *
     * <p>Rethrows the original exception rather than the
     * {@link CompletionException} wrapping it, so a synchronous caller sees the
     * same failure it would have seen doing the work itself.</p>
     *
     * @param <T>               the result type
     * @param completableFuture the future to wait on
     * @return the completed value
     */
    private <T> T joinSynchronously(final CompletableFuture<T> completableFuture) {
        try {
            return completableFuture.join();
        } catch (final CompletionException exception) {
            final Throwable throwable = exception.getCause();

            if (throwable instanceof final RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (throwable instanceof final Error error) {
                throw error;
            }

            throw exception;
        }
    }
}