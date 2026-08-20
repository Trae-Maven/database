package io.github.trae.database.storage;

import io.github.trae.database.driver.RedisDriver;
import io.github.trae.utilities.UtilString;
import io.lettuce.core.KeyValue;
import io.lettuce.core.SetArgs;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * <p>Subclasses supply {@link #serialize(Object)} and
 * {@link #deserialize(String)}, plus the {@link #index(Object)} and
 * {@link #unIndex(Object)} rules deciding which key an entity lives under.</p>
 *
 * @param <Value> the cached value type
 */
@AllArgsConstructor
public abstract class RedisStorage<Value> implements Storage<String, Value> {

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
        if (UtilString.isEmpty(key) || value == null) {
            return;
        }

        final String resolvedKey = this.resolveKey(key);
        final String serializedValue = this.serialize(value);

        final Duration ttl = this.getTTL();

        this.redisDriver.useResource(commands -> {
            if (ttl == null) {
                commands.set(resolvedKey, serializedValue);
            } else {
                commands.set(resolvedKey, serializedValue, SetArgs.Builder.px(ttl.toMillis()));
            }

            commands.sadd(this.resolveIndexKey(), key);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the entry and drops its key from the namespace index.</p>
     */
    @Override
    public void remove(final String key) {
        if (UtilString.isEmpty(key)) {
            return;
        }

        this.redisDriver.useResource(commands -> {
            commands.del(this.resolveKey(key));
            commands.srem(this.resolveIndexKey(), key);
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>A miss prunes the key from the namespace index, cleaning up after
     * Redis-side expiry.</p>
     */
    @Override
    public Optional<Value> get(final String key) {
        if (UtilString.isEmpty(key)) {
            return Optional.empty();
        }

        final String value = this.redisDriver.getResource(commands -> commands.get(this.resolveKey(key)));

        if (value == null) {
            this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), key));
            return Optional.empty();
        }

        return Optional.of(this.deserialize(value));
    }

    /**
     * {@inheritDoc}
     *
     * <p>A miss prunes the key from the namespace index.</p>
     */
    @Override
    public boolean contains(final String key) {
        if (UtilString.isEmpty(key)) {
            return false;
        }

        final boolean contains = this.redisDriver.getResource(commands -> commands.exists(this.resolveKey(key))) > 0;

        if (!contains) {
            this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), key));
        }

        return contains;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the namespace index, then verifies each key with a single
     * {@code MGET}, dropping any whose value has expired.</p>
     */
    @Override
    public Set<String> keys() {
        final Set<String> keySet = new HashSet<>(this.redisDriver.getResource(commands -> commands.smembers(this.resolveIndexKey())));

        if (keySet.isEmpty()) {
            return keySet;
        }

        final List<KeyValue<String, String>> keyValueList = this.redisDriver.getResource(commands -> commands.mget(keySet.stream().map(this::resolveKey).toArray(String[]::new)));

        final Set<String> expiredKeySet = new HashSet<>();

        keyValueList.forEach(keyValue -> {
            if (!keyValue.hasValue()) {
                expiredKeySet.add(this.unresolveKey(keyValue.getKey()));
            }
        });

        if (!expiredKeySet.isEmpty()) {
            this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), expiredKeySet.toArray(String[]::new)));
            keySet.removeAll(expiredKeySet);
        }

        return keySet;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Fetches every indexed key in one {@code MGET} and deserialises the
     * values that are still present, pruning the rest from the index.</p>
     */
    @Override
    public List<Value> values() {
        final Set<String> keySet = this.redisDriver.getResource(commands -> commands.smembers(this.resolveIndexKey()));
        if (keySet.isEmpty()) {
            return List.of();
        }

        final List<Value> valueList = new ArrayList<>();
        final Set<String> expiredKeySet = new HashSet<>();

        this.redisDriver.getResource(commands -> commands.mget(
                keySet.stream()
                        .map(this::resolveKey)
                        .toArray(String[]::new)
        )).forEach(keyValue -> {
            if (keyValue.hasValue()) {
                valueList.add(this.deserialize(keyValue.getValue()));
            } else {
                expiredKeySet.add(this.unresolveKey(keyValue.getKey()));
            }
        });

        if (!expiredKeySet.isEmpty()) {
            this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), expiredKeySet.toArray(String[]::new)));
        }

        return valueList;
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
        final Set<String> keySet = this.redisDriver.getResource(commands -> commands.smembers(this.resolveIndexKey()));
        if (keySet.isEmpty()) {
            return 0;
        }

        final Set<String> expiredKeySet = new HashSet<>();

        final long size = this.redisDriver.getResource(commands -> commands.mget(keySet.stream().map(this::resolveKey).toArray(String[]::new)))
                .stream()
                .filter(keyValue -> {
                    if (keyValue.hasValue()) {
                        return true;
                    }

                    expiredKeySet.add(this.unresolveKey(keyValue.getKey()));
                    return false;
                }).count();

        if (!expiredKeySet.isEmpty()) {
            this.redisDriver.useResource(commands -> commands.srem(this.resolveIndexKey(), expiredKeySet.toArray(String[]::new)));
        }

        return size;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unlinks every entry in the namespace and deletes the index itself.
     * {@code UNLINK} frees the values on a background thread, so a large
     * namespace does not stall Redis.</p>
     */
    @Override
    public void clear() {
        final Set<String> keySet = this.redisDriver.getResource(commands -> commands.smembers(this.resolveIndexKey()));
        if (keySet.isEmpty()) {
            this.redisDriver.useResource(commands -> commands.del(this.resolveIndexKey()));
            return;
        }

        this.redisDriver.useResource(commands -> {
            commands.unlink(keySet.stream().map(this::resolveKey).toArray(String[]::new));

            commands.del(this.resolveIndexKey());
        });
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
    public void reIndex(final Value value, final String previousKey) {
        this.remove(previousKey);
        this.index(value);
    }

    /**
     * Prefixes a key with this storage's namespace.
     *
     * @param key the bare key
     * @return the full Redis key
     */
    private String resolveKey(final String key) {
        return this.namespace + ":" + key;
    }

    /**
     * Strips this storage's namespace from a full Redis key.
     *
     * @param key the full Redis key
     * @return the bare key as held in the namespace index
     */
    private String unresolveKey(final String key) {
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