package io.github.trae.database.storage;

import io.github.trae.database.constants.Constants;
import io.github.trae.database.storage.interfaces.IRedisStorage;
import io.github.trae.database.storage.interfaces.Storage;
import io.github.trae.database.types.redis.RedisDatabaseDriver;
import io.github.trae.utilities.UtilGeneric;
import io.github.trae.utilities.UtilJava;
import io.github.trae.utilities.UtilString;
import lombok.AllArgsConstructor;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Jedis-backed distributed implementation of {@link Storage} with native Redis TTL
 * via {@code SET ... EX}.
 *
 * <p>Keys are automatically prefixed with a configurable namespace using the format
 * {@code {redisKey}:{key}} to avoid collisions across different storage instances.
 * Values are serialized to JSON via {@link Constants#GSON} and the {@code Value} type
 * is resolved at runtime via {@link UtilGeneric#getGenericParameter}.</p>
 *
 * <p>All scan-based operations ({@link #flush}, {@link #getKeys}, {@link #getValues},
 * {@link #getSize}) use {@code SCAN} with a batch count of 100 instead of {@code KEYS}
 * to avoid blocking the Redis server. Value retrieval uses {@code MGET} for batch
 * efficiency within each scan iteration.</p>
 *
 * @param <Value> the value type
 * @see Storage
 * @see RedisDatabaseDriver
 */
@AllArgsConstructor
public abstract class RedisStorage<Value> implements IRedisStorage<Value> {

    private final RedisDatabaseDriver redisDatabaseDriver;
    private final String redisKey;

    /**
     * Stores a value in Redis. When a TTL is provided it is applied natively via
     * {@code SET ... EX}; a {@code null} TTL stores the value without expiry.
     *
     * @param key   the key to store under (will be prefixed with {@link #redisKey})
     * @param value the value to store (serialized to JSON)
     * @param ttl   the time-to-live duration, or {@code null} for no expiry
     */
    @Override
    public void put(final String key, final Value value, final Duration ttl) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null.");
        }

        this.redisDatabaseDriver.useResource(jedis -> {
            if (ttl == null) {
                jedis.set(this.key(key), Constants.GSON.toJson(value));
            } else {
                jedis.set(this.key(key), Constants.GSON.toJson(value), SetParams.setParams().ex(ttl.toSeconds()));
            }
        });
    }

    @Override
    public void put(final String key, final Value value) {
        this.put(key, value, null);
    }

    /**
     * Deletes a key from Redis.
     *
     * @param key the key to remove (will be prefixed with {@link #redisKey})
     */
    @Override
    public void remove(final String key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null.");
        }

        this.redisDatabaseDriver.useResource(jedis -> jedis.del(this.key(key)));
    }

    /**
     * Replaces an entry under a new key. Deletes the previous key first, then
     * stores the value under the new key if both key and value are non-null,
     * applying the TTL via {@code SET ... EX} when provided. The delete and set
     * run on a single connection but are two separate commands, not a transaction.
     *
     * @param previousKey the old key to delete
     * @param key         the new key to store under
     * @param value       the value to store (serialized to JSON)
     * @param ttl         the time-to-live duration, or {@code null} for no expiry
     */
    @Override
    public void update(final String previousKey, final String key, final Value value, final Duration ttl) {
        if (previousKey == null) {
            throw new IllegalArgumentException("Previous Key cannot be null.");
        }

        this.redisDatabaseDriver.useResource(jedis -> {
            jedis.del(this.key(previousKey));

            if (key != null && value != null) {
                if (ttl == null) {
                    jedis.set(this.key(key), Constants.GSON.toJson(value));
                } else {
                    jedis.set(this.key(key), Constants.GSON.toJson(value), SetParams.setParams().ex(ttl.toSeconds()));
                }
            }
        });
    }

    @Override
    public void update(final String previousKey, final String key, final Value value) {
        this.update(previousKey, key, value, null);
    }

    /**
     * Retrieves and deserializes a value from Redis.
     *
     * <p>The {@code Value} class is resolved at runtime via
     * {@link UtilGeneric#getGenericParameter} for Gson deserialization.</p>
     *
     * @param key the key to look up (will be prefixed with {@link #redisKey})
     * @return the deserialized value if present, otherwise empty
     */
    @SuppressWarnings("unchecked")
    @Override
    public Optional<Value> get(final String key) {
        if (UtilString.isEmpty(key)) {
            return Optional.empty();
        }

        return this.redisDatabaseDriver.getResource(jedis -> Optional.ofNullable(jedis.get(this.key(key))).map(value -> Constants.GSON.fromJson(value, (Class<Value>) UtilGeneric.getGenericParameter(this.getClass(), RedisStorage.class, 0))));
    }

    /**
     * Retrieves a value, or loads it via the supplied loader on a cache miss, using a
     * distributed lock to prevent stampedes across instances.
     *
     * <p>On a miss, the first caller across all instances acquires a short-lived lock
     * via {@code SET NX EX}, runs the loader, and populates the cache. Concurrent callers
     * that fail to acquire the lock wait and re-read, served the freshly populated value
     * rather than each invoking the loader. If the lock holder does not populate the key
     * within {@code maxRetries} attempts, waiters fall through and invoke the loader
     * directly as a safety valve.</p>
     *
     * @param key        the key to look up (will be prefixed with {@link #redisKey})
     * @param ttl        the time-to-live applied to a loaded value, or {@code null} for no expiry
     * @param loader     supplies the value on a miss; an empty result is not cached
     * @param lockTtl    the lock's safety expiry, guarding against a crashed loader
     * @param maxRetries how many times a lock-loser re-reads before falling through to the loader
     * @param retryDelay how long to wait between re-reads
     * @return the cached or freshly loaded value, or empty if the loader returns empty
     */
    public Optional<Value> getOrLoad(final String key, final Duration ttl, final Supplier<Optional<Value>> loader, final Duration lockTtl, final int maxRetries, final Duration retryDelay) {
        final Optional<Value> cached = this.get(key);

        if (cached.isPresent()) {
            return cached;
        }

        final String lockKey = "lock:%s".formatted(this.key(key));
        final String lockToken = UUID.randomUUID().toString();

        final boolean acquired = this.redisDatabaseDriver.getResource(jedis -> "OK".equals(jedis.set(lockKey, lockToken, SetParams.setParams().nx().ex(lockTtl.toSeconds()))));

        if (acquired) {
            try {
                final Optional<Value> recheck = this.get(key);

                if (recheck.isPresent()) {
                    return recheck;
                }

                final Optional<Value> loaded = loader.get();

                loaded.ifPresent(value -> this.put(key, value, ttl));

                return loaded;
            } finally {
                this.releaseLock(lockKey, lockToken);
            }
        }

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                Thread.sleep(retryDelay.toMillis());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();

                break;
            }

            final Optional<Value> retry = this.get(key);

            if (retry.isPresent()) {
                return retry;
            }
        }

        return loader.get();
    }

    /**
     * Checks whether a key exists in Redis.
     *
     * @param key the key to check (will be prefixed with {@link #redisKey})
     * @return {@code true} if the key exists
     */
    @Override
    public boolean contains(final String key) {
        if (UtilString.isEmpty(key)) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }

        return this.redisDatabaseDriver.getResource(jedis -> jedis.exists(this.key(key)));
    }

    /**
     * Deletes all keys matching this storage's prefix using {@code SCAN} + batch {@code DEL}.
     *
     * <p>Iterates in batches of 100 to avoid blocking the Redis server.</p>
     */
    @Override
    public void flush() {
        this.redisDatabaseDriver.useResource(jedis -> {
            final ScanParams scanParams = new ScanParams().match(this.key("*")).count(100);

            String cursor = ScanParams.SCAN_POINTER_START;

            do {
                final ScanResult<String> scanResult = jedis.scan(cursor, scanParams);

                final List<String> keys = scanResult.getResult();

                if (!(keys.isEmpty())) {
                    jedis.del(keys.toArray(String[]::new));
                }

                cursor = scanResult.getCursor();
            } while (!(cursor.equals(ScanParams.SCAN_POINTER_START)));
        });
    }

    /**
     * Returns all keys matching this storage's prefix with the prefix stripped.
     *
     * <p>Uses {@code SCAN} to iterate in batches. Each returned key has the
     * {@code {redisKey}:} prefix removed, returning the raw key as originally stored.</p>
     *
     * @return a list of raw keys (without the Redis prefix)
     */
    @Override
    public List<String> getKeys() {
        return this.redisDatabaseDriver.getResource(jedis -> {
            final ScanParams scanParams = new ScanParams().match(this.key("*")).count(100);

            final int prefixLength = this.redisKey.length() + 1;

            return UtilJava.createCollection(new ArrayList<>(), list -> {
                String cursor = ScanParams.SCAN_POINTER_START;

                do {
                    final ScanResult<String> scanResult = jedis.scan(cursor, scanParams);

                    for (final String key : scanResult.getResult()) {
                        list.add(key.substring(prefixLength));
                    }

                    cursor = scanResult.getCursor();
                } while (!(cursor.equals(ScanParams.SCAN_POINTER_START)));
            });
        });
    }

    /**
     * Returns all values matching this storage's prefix.
     *
     * <p>Uses {@code SCAN} to discover keys in batches, then {@code MGET} to
     * retrieve values in bulk within each iteration. Null results (expired keys
     * between scan and fetch) are silently skipped.</p>
     *
     * @return a list of deserialized values
     */
    @SuppressWarnings("unchecked")
    @Override
    public List<Value> getValues() {
        return this.redisDatabaseDriver.getResource(jedis -> {
            final ScanParams scanParams = new ScanParams().match(this.key("*")).count(100);

            return UtilJava.createCollection(new ArrayList<>(), list -> {
                String cursor = ScanParams.SCAN_POINTER_START;

                do {
                    final ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    final List<String> keys = scanResult.getResult();

                    if (!(keys.isEmpty())) {
                        for (final String json : jedis.mget(keys.toArray(String[]::new))) {
                            if (json != null) {
                                list.add(Constants.GSON.fromJson(json, (Class<Value>) UtilGeneric.getGenericParameter(this.getClass(), RedisStorage.class, 0)));
                            }
                        }
                    }

                    cursor = scanResult.getCursor();
                } while (!(cursor.equals(ScanParams.SCAN_POINTER_START)));
            });
        });
    }

    /**
     * Returns the number of keys matching this storage's prefix.
     *
     * <p>Uses {@code SCAN} to count keys in batches without loading values.</p>
     *
     * @return the total number of matching keys
     */
    @Override
    public int getSize() {
        return this.redisDatabaseDriver.getResource(jedis -> {
            final ScanParams scanParams = new ScanParams().match(this.key("*")).count(100);

            int count = 0;

            String cursor = ScanParams.SCAN_POINTER_START;

            do {
                final ScanResult<String> scanResult = jedis.scan(cursor, scanParams);

                count += scanResult.getResult().size();

                cursor = scanResult.getCursor();
            } while (!(cursor.equals(ScanParams.SCAN_POINTER_START)));

            return count;
        });
    }

    /**
     * Checks whether any keys exist under this storage's prefix.
     *
     * @return {@code true} if no matching keys exist
     */
    @Override
    public boolean isEmpty() {
        return this.getSize() <= 0;
    }

    /**
     * Builds the full Redis key by prefixing with the storage namespace.
     *
     * @param key the raw key
     * @return the prefixed key in the format {@code {redisKey}:{key}}
     */
    private String key(final String key) {
        return "%s:%s".formatted(this.redisKey, key);
    }

    /**
     * Releases a distributed lock only if the caller still owns it, comparing the
     * stored token against the expected token via an atomic {@code GET}/{@code DEL}
     * Lua script. This prevents deleting a lock that has expired and been re-acquired
     * by another instance.
     *
     * @param lockKey   the full (already prefixed) lock key
     * @param lockToken the token proving ownership of the lock
     */
    private void releaseLock(final String lockKey, final String lockToken) {
        this.redisDatabaseDriver.useResource(jedis -> jedis.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", List.of(lockKey), List.of(lockToken)));
    }
}