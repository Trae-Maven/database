package io.github.trae.database.storage;

import io.github.trae.database.driver.RedisDriver;
import io.github.trae.utilities.UtilString;
import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import lombok.AllArgsConstructor;
import lombok.CustomLog;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Redis-backed cache tier, shared by every process pointing at the same Redis.
 *
 * <p>Sits between {@link LocalStorage} and the database: slower than a map
 * lookup, far cheaper than a query, and visible across a whole network of
 * servers, so one server warming an entity serves the rest.</p>
 *
 * <p>Every key is prefixed with this storage's namespace, and each namespace also
 * keeps a Redis set of its member keys. That index is what makes
 * {@link #keys()}, {@link #values()} and {@link #size()} possible without a
 * {@code SCAN} — Redis expires individual entries but cannot remove them from a
 * set, so those methods detect entries whose value has gone and prune the index
 * as they go.</p>
 *
 * <p>Those three read the whole namespace, in {@value #CHUNK_SIZE}-key batches
 * so no single command blocks Redis, and deserialise every value they find.
 * They are proportional to the namespace size and belong in administrative
 * paths, not on a per-request lookup.</p>
 *
 * <p>A value that will not deserialise — whether it throws or decodes to
 * {@code null} — is treated as a miss and evicted rather than returned, so a
 * schema change poisons nothing and nothing is left behind to fail the same way
 * on the next read. The entity is simply refetched from the database and cached
 * again in its new shape.</p>
 *
 * <p>Every collection returned is unmodifiable, matching {@link LocalStorage}.</p>
 *
 * <p>Subclasses supply {@link #serialize(Object)} and
 * {@link #deserialize(String)}, plus the {@link #index(Object)} and
 * {@link #unIndex(Object)} rules deciding which key an entity lives under, and
 * may override {@link Storage#resolveKey(Object)} to normalise keys.</p>
 *
 * @param <Value>      the type held under a key — the entity itself, or an
 *                     identifier pointing at it
 * @param <IndexValue> the entity type the index rules operate on
 */
@CustomLog
@AllArgsConstructor
public abstract class RedisStorage<Value, IndexValue> implements Storage<String, Value, IndexValue> {

    /**
     * How many keys are sent in one {@code MGET} or {@code UNLINK}.
     */
    private static final int CHUNK_SIZE = 512;

    /**
     * The connection used for every command.
     */
    private final RedisDriver redisDriver;

    /**
     * Prefix applied to every key this storage owns, keeping namespaces from
     * colliding in a shared Redis.
     */
    private final String namespace;

    /**
     * {@inheritDoc}
     *
     * <p>Writes the value and adds the key to the namespace index in the same
     * round trip. A null time-to-live stores the entry without expiry.</p>
     */
    @Override
    public void put(final String key, final Value value) {
        final String resolvedKey = this.resolveValidKey(key);
        if (resolvedKey == null || value == null) {
            return;
        }

        final String namespacedKey = this.namespaceKey(resolvedKey);
        final String serializedValue = this.serialize(value);

        final Duration ttl = this.getTTL();

        this.redisDriver.useResource(commands -> {
            if (ttl == null) {
                commands.set(namespacedKey, serializedValue);
            } else {
                commands.set(namespacedKey, serializedValue, SetArgs.Builder.px(ttl.toMillis()));
            }

            commands.sadd(this.resolveIndexKey(), resolvedKey);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the entry and drops its key from the namespace index.</p>
     */
    @Override
    public void remove(final String key) {
        final String resolvedKey = this.resolveValidKey(key);
        if (resolvedKey == null) {
            return;
        }

        this.evict(resolvedKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A miss costs one round trip and leaves the index alone — the vast
     * majority of misses are keys that were never cached, and pruning each one
     * would double the cost of every cold lookup. Stale index entries are
     * cleaned up by {@link #keys()}, {@link #values()} and {@link #size()}.</p>
     */
    @Override
    public Optional<Value> get(final String key) {
        final String resolvedKey = this.resolveValidKey(key);
        if (resolvedKey == null) {
            return Optional.empty();
        }

        final String value = this.redisDriver.getResource(commands -> commands.get(this.namespaceKey(resolvedKey)));
        if (value == null) {
            return Optional.empty();
        }

        final Value deserializedValue = this.deserializeOrNull(resolvedKey, value);

        if (deserializedValue == null) {
            this.evict(resolvedKey);
            return Optional.empty();
        }

        return Optional.of(deserializedValue);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(final String key) {
        final String resolvedKey = this.resolveValidKey(key);
        if (resolvedKey == null) {
            return false;
        }

        return this.redisDriver.getResource(commands -> commands.exists(this.namespaceKey(resolvedKey))) > 0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the namespace index, then verifies each key with chunked
     * {@code MGET}s, dropping any whose value has expired.</p>
     */
    @Override
    public Set<String> keys() {
        final Set<String> keySet = new HashSet<>(this.indexKeySet());
        if (keySet.isEmpty()) {
            return Collections.emptySet();
        }

        final Set<String> expiredKeySet = new HashSet<>();

        this.forEachEntry(keySet, keyValue -> {
            if (!keyValue.hasValue()) {
                expiredKeySet.add(this.unNamespaceKey(keyValue.getKey()));
            }
        });

        if (!expiredKeySet.isEmpty()) {
            this.pruneIndex(expiredKeySet);
            keySet.removeAll(expiredKeySet);
        }

        return Collections.unmodifiableSet(keySet);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches every indexed key in chunked {@code MGET}s and deserialises the
     * values that are still present, pruning expired keys from the index and
     * evicting any that will not decode.</p>
     */
    @Override
    public List<Value> values() {
        final Set<String> keySet = this.indexKeySet();
        if (keySet.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Value> valueList = new ArrayList<>();
        final Set<String> expiredKeySet = new HashSet<>();
        final Set<String> corruptKeySet = new HashSet<>();

        this.forEachEntry(keySet, keyValue -> {
            final String key = this.unNamespaceKey(keyValue.getKey());

            if (!keyValue.hasValue()) {
                expiredKeySet.add(key);
                return;
            }

            final Value value = this.deserializeOrNull(key, keyValue.getValue());

            if (value == null) {
                corruptKeySet.add(key);
                return;
            }

            valueList.add(value);
        });

        if (!expiredKeySet.isEmpty()) {
            this.pruneIndex(expiredKeySet);
        }

        if (!corruptKeySet.isEmpty()) {
            this.forEachChunk(corruptKeySet, namespacedKeys -> this.redisDriver.useResource(commands -> commands.unlink(namespacedKeys)));
            this.pruneIndex(corruptKeySet);
        }

        return Collections.unmodifiableList(valueList);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Counts only keys whose values still exist, pruning the index of any that
     * have expired. Not a plain {@code SCARD}, since the index can outlive the
     * entries it names.</p>
     */
    @Override
    public long size() {
        return this.keys().size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unlinks every entry in the namespace and deletes the index itself.
     * {@code UNLINK} frees the values on a background thread, so a large
     * namespace does not stall Redis.</p>
     *
     * <p>Not atomic — the member keys are read first, so an entry written by
     * another server between the read and the unlink survives, orphaned from the
     * index until its time-to-live runs out.</p>
     */
    @Override
    public void clear() {
        final Set<String> keySet = this.indexKeySet();

        if (!keySet.isEmpty()) {
            this.forEachChunk(keySet, namespacedKeys -> this.redisDriver.useResource(commands -> commands.unlink(namespacedKeys)));
        }

        this.redisDriver.useResource(commands -> commands.del(this.resolveIndexKey()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two round trips — a {@code DEL} plus {@code SREM} for the old key, then
     * a {@code SET} plus {@code SADD} for the new one. Skipping it leaves the old
     * key serving a stale entity to every server on the network until its
     * time-to-live runs out.</p>
     *
     * <p>The entity must already hold its new value; the previous key is passed
     * in because it can no longer be derived from the entity.</p>
     */
    @Override
    public void reIndex(final IndexValue indexValue, final String previousKey) {
        this.remove(previousKey);
        this.index(indexValue);
    }

    /**
     * Decodes a stored value, returning {@code null} if it throws or decodes to
     * nothing.
     *
     * <p>Both read paths report a bad value the same way because the logging
     * lives here; the caller decides how to evict it.</p>
     *
     * @param key   the resolved key the value was stored under
     * @param value the encoded form
     * @return the reconstructed value, or {@code null} if it is unusable
     */
    private Value deserializeOrNull(final String key, final String value) {
        try {
            final Value deserializedValue = this.deserialize(value);

            if (deserializedValue == null) {
                LOGGER.error("Deserialized null for {}:{}, evicting", this.namespace, key);
            }

            return deserializedValue;
        } catch (final Exception exception) {
            LOGGER.error("Failed to deserialize {}:{}, evicting", this.namespace, key, exception);

            return null;
        }
    }

    /**
     * Deletes an entry and drops its key from the namespace index.
     *
     * @param key the resolved key to evict
     */
    private void evict(final String key) {
        this.redisDriver.useResource(commands -> {
            commands.del(this.namespaceKey(key));
            commands.srem(this.resolveIndexKey(), key);
        });
    }

    /**
     * Returns every key the namespace index names, live or not.
     *
     * @return the indexed key set
     */
    private Set<String> indexKeySet() {
        return this.redisDriver.getResource(commands -> commands.smembers(this.resolveIndexKey()));
    }

    /**
     * Drops keys from the namespace index.
     *
     * @param keySet the resolved keys to remove
     */
    private void pruneIndex(final Set<String> keySet) {
        this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), keySet.toArray(String[]::new)));
    }

    /**
     * Fetches every given key in {@value #CHUNK_SIZE}-key batches, handing each
     * result to the consumer.
     *
     * @param keySet   the resolved keys to fetch
     * @param consumer called once per key with its value, present or not
     */
    private void forEachEntry(final Set<String> keySet, final Consumer<KeyValue<String, String>> consumer) {
        this.forEachChunk(keySet, namespacedKeys -> this.redisDriver.getResource(commands -> commands.mget(namespacedKeys)).forEach(consumer));
    }

    /**
     * Splits keys into {@value #CHUNK_SIZE}-key batches, namespacing each batch,
     * so one command never carries an unbounded argument list.
     *
     * @param keySet   the resolved keys to split
     * @param consumer called once per batch with the namespaced keys
     */
    private void forEachChunk(final Set<String> keySet, final Consumer<String[]> consumer) {
        final List<String> keyList = List.copyOf(keySet);

        for (int index = 0; index < keyList.size(); index += CHUNK_SIZE) {
            consumer.accept(keyList.subList(index, Math.min(index + CHUNK_SIZE, keyList.size())).stream().map(this::namespaceKey).toArray(String[]::new));
        }
    }

    /**
     * Validates a key, resolves it, then validates the result.
     *
     * @param key the key as supplied
     * @return the resolved key, or {@code null} if either form is unusable
     */
    private String resolveValidKey(final String key) {
        if (UtilString.isEmpty(key)) {
            return null;
        }

        final String resolvedKey = this.resolveKey(key);

        return UtilString.isEmpty(resolvedKey) ? null : resolvedKey;
    }

    /**
     * Prefixes a resolved key with this storage's namespace.
     *
     * @param key the resolved key
     * @return the full Redis key
     */
    private String namespaceKey(final String key) {
        return this.namespace + ":" + key;
    }

    /**
     * Strips this storage's namespace from a full Redis key.
     *
     * @param key the full Redis key
     * @return the resolved key as held in the namespace index
     */
    private String unNamespaceKey(final String key) {
        return key.substring(this.namespace.length() + 1);
    }

    /**
     * Returns the key of the Redis set holding this namespace's member keys.
     *
     * @return the namespace index key
     */
    private String resolveIndexKey() {
        return this.namespace + ":__index";
    }

    /**
     * Encodes a value for storage.
     *
     * @param value the value to encode
     * @return the encoded form
     */
    protected abstract String serialize(final Value value);

    /**
     * Decodes a stored value.
     *
     * @param value the encoded form
     * @return the reconstructed value
     */
    protected abstract Value deserialize(final String value);
}