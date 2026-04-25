package io.github.trae.database.storage;

import io.github.trae.database.storage.cache.Cache;
import io.github.trae.database.storage.interfaces.Storage;
import io.github.trae.utilities.UtilTime;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ConcurrentHashMap}-backed in-memory implementation of {@link Storage}
 * with per-key TTL support.
 *
 * <p>Each entry is wrapped in a {@link Cache} object that tracks its creation
 * time and TTL duration. Expiry is enforced in two ways:</p>
 * <ul>
 *     <li><b>Lazy eviction</b> — on every {@link #get} call, if the requested
 *     entry has expired it is removed and {@link Optional#empty()} is returned.</li>
 *     <li><b>Batched background sweep</b> — triggered on {@link #get} calls once
 *     per minute, removing up to {@value #EVICTION_BATCH_SIZE} expired entries per
 *     pass to prevent memory buildup without causing lag spikes. If more expired
 *     entries remain, the next {@link #get} call continues immediately.</li>
 * </ul>
 *
 * <p>Read methods ({@link #getKeys}, {@link #getValues}, {@link #getSize}) filter
 * out expired entries from their results.</p>
 *
 * @param <Key>   the key type
 * @param <Value> the value type
 * @see Storage
 * @see Cache
 */
public abstract class LocalStorage<Key, Value> implements Storage<Key, Value> {

    /**
     * Maximum number of expired entries removed per eviction pass.
     */
    private static final int EVICTION_BATCH_SIZE = 10_000;

    /**
     * Minimum interval between eviction sweeps in milliseconds.
     */
    private static final long EVICTION_INTERVAL = Duration.ofMinutes(1).toMillis();

    /**
     * Timestamp of the last completed eviction sweep, shared across all instances.
     */
    private static long lastEviction = System.currentTimeMillis();

    private final ConcurrentHashMap<Key, Cache<Value>> map = new ConcurrentHashMap<>();

    /**
     * Stores a value with the given TTL. A {@code null} TTL creates a permanent entry.
     *
     * @param key   the key to store under
     * @param value the value to store
     * @param ttl   the time-to-live duration, or {@code null} for permanent
     */
    @Override
    public void put(final Key key, final Value value, final Duration ttl) {
        this.map.put(key, new Cache<>(value, ttl));
    }

    /**
     * Removes the entry for the given key.
     *
     * @param key the key to remove
     */
    @Override
    public void remove(final Key key) {
        this.map.remove(key);
    }

    /**
     * Replaces an entry under a new key. Removes the previous key first, then
     * stores the value under the new key if both key and value are non-null.
     *
     * @param previousKey the old key to remove
     * @param key         the new key to store under
     * @param value       the value to store
     * @param ttl         the time-to-live duration, or {@code null} for permanent
     */
    @Override
    public void update(final Key previousKey, final Key key, final Value value, final Duration ttl) {
        this.map.remove(previousKey);

        if (key != null && value != null) {
            this.map.put(key, new Cache<>(value, ttl));
        }
    }

    /**
     * Retrieves the value for the given key if it exists and has not expired.
     *
     * <p>Before performing the lookup, triggers a batched eviction sweep if the
     * eviction interval has elapsed. Removes up to {@value #EVICTION_BATCH_SIZE}
     * expired entries per pass — if fewer than that were removed, the sweep is
     * complete and the timer resets. Otherwise the next call continues immediately.</p>
     *
     * <p>If the requested entry itself has expired, it is lazily removed and
     * {@link Optional#empty()} is returned.</p>
     *
     * @param key the key to look up
     * @return the value if present and valid, otherwise empty
     */
    @Override
    public Optional<Value> get(final Key key) {
        if (UtilTime.elapsed(lastEviction, EVICTION_INTERVAL)) {
            int count = 0;
            final Iterator<Cache<Value>> iterator = this.map.values().iterator();

            while (iterator.hasNext() && count < EVICTION_BATCH_SIZE) {
                if (!(iterator.next().isValid())) {
                    iterator.remove();
                    count++;
                }
            }
            if (count < EVICTION_BATCH_SIZE) {
                lastEviction = System.currentTimeMillis();
            }
        }

        final Cache<Value> cache = this.map.get(key);
        if (cache != null) {
            if (cache.isValid()) {
                return Optional.of(cache.getValue());
            }

            this.map.remove(key);
        }

        return Optional.empty();
    }

    /**
     * Checks whether a valid (non-expired) entry exists for the given key.
     *
     * @param key the key to check
     * @return {@code true} if the key maps to a valid entry
     */
    @Override
    public boolean contains(final Key key) {
        return this.get(key).isPresent();
    }

    /**
     * Removes all entries from the storage.
     */
    @Override
    public void flush() {
        this.map.clear();
    }

    /**
     * Returns all keys that map to valid (non-expired) entries.
     *
     * @return an immutable list of valid keys
     */
    @Override
    public List<Key> getKeys() {
        return this.map.entrySet().stream().filter(entry -> entry.getValue().isValid()).map(Map.Entry::getKey).toList();
    }

    /**
     * Returns all values from valid (non-expired) entries.
     *
     * @return an immutable list of valid values
     */
    @Override
    public List<Value> getValues() {
        return this.map.values().stream().filter(Cache::isValid).map(Cache::getValue).toList();
    }

    /**
     * Returns the count of valid (non-expired) entries.
     *
     * @return the number of valid entries
     */
    @Override
    public int getSize() {
        return (int) this.map.values().stream().filter(Cache::isValid).count();
    }

    /**
     * Checks whether the storage contains any valid entries.
     *
     * @return {@code true} if no valid entries exist
     */
    @Override
    public boolean isEmpty() {
        return this.getSize() <= 0;
    }
}