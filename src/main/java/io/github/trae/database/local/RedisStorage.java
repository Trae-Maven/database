package io.github.trae.database.local;

import io.github.trae.database.constants.Constants;
import io.github.trae.database.local.interfaces.Storage;
import io.github.trae.database.types.redis.RedisDatabaseDriver;
import io.github.trae.utilities.UtilGeneric;
import io.github.trae.utilities.UtilJava;
import lombok.AllArgsConstructor;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Jedis-backed distributed implementation of {@link Storage} with native Redis TTL
 * via {@code SETEX}.
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
public abstract class RedisStorage<Value> implements Storage<String, Value> {

    private final RedisDatabaseDriver redisDatabaseDriver;
    private final String redisKey;

    /**
     * Stores a value in Redis with the given TTL using {@code SETEX}.
     *
     * @param key   the key to store under (will be prefixed with {@link #redisKey})
     * @param value the value to store (serialized to JSON)
     * @param ttl   the time-to-live duration
     */
    @Override
    public void put(final String key, final Value value, final Duration ttl) {
        this.redisDatabaseDriver.useResource(jedis -> jedis.setex(this.key(key), ttl.toSeconds(), Constants.GSON.toJson(value)));
    }

    /**
     * Deletes a key from Redis.
     *
     * @param key the key to remove (will be prefixed with {@link #redisKey})
     */
    @Override
    public void remove(final String key) {
        this.redisDatabaseDriver.useResource(jedis -> jedis.del(this.key(key)));
    }

    /**
     * Replaces an entry under a new key. Deletes the previous key first, then
     * stores the value under the new key with {@code SETEX} if both key and
     * value are non-null.
     *
     * @param previousKey the old key to delete
     * @param key         the new key to store under
     * @param value       the value to store (serialized to JSON)
     * @param ttl         the time-to-live duration
     */
    @Override
    public void update(final String previousKey, final String key, final Value value, final Duration ttl) {
        this.redisDatabaseDriver.useResource(jedis -> {
            jedis.del(this.key(previousKey));

            if (key != null && value != null) {
                jedis.setex(this.key(key), ttl.toSeconds(), Constants.GSON.toJson(value));
            }
        });
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
        return this.redisDatabaseDriver.getResource(jedis -> Optional.ofNullable(jedis.get(this.key(key))).map(value -> Constants.GSON.fromJson(value, (Class<Value>) UtilGeneric.getGenericParameter(this.getClass(), RedisStorage.class, 1))));
    }

    /**
     * Checks whether a key exists in Redis.
     *
     * @param key the key to check (will be prefixed with {@link #redisKey})
     * @return {@code true} if the key exists
     */
    @Override
    public boolean contains(final String key) {
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
                                list.add(Constants.GSON.fromJson(json, (Class<Value>) UtilGeneric.getGenericParameter(this.getClass(), RedisStorage.class, 1)));
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
}