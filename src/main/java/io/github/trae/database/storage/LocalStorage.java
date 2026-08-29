package io.github.trae.database.storage;

import io.github.trae.database.storage.data.CacheEntry;
import io.github.trae.utilities.UtilString;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * In-process cache tier, backed by a {@link ConcurrentHashMap} of keys to
 * expiring entries.
 *
 * <p>The fastest of the three tiers and the first one a lookup consults. Entries
 * expire by the time-to-live returned from {@link #getTTL()}, applied when a
 * value is written; reads do not extend it.</p>
 *
 * <p>There is no background sweeper. Expired entries are dropped when read, and
 * a full sweep runs every {@value #CLEANUP_THRESHOLD} operations, so an entry
 * nobody asks for in a quiet storage still gets collected eventually without a
 * scheduler.</p>
 *
 * <p>A storage is unbounded unless {@link #getMaxSize()} is overridden. That
 * suits a working set with a natural ceiling — the players on a server, say —
 * but a public endpoint caches whatever gets requested, so a storage reachable
 * that way wants a bound. A write is never refused: at capacity the storage
 * sweeps expired entries first and, if that frees nothing, drops the entries
 * closest to expiring.</p>
 *
 * <p>Subclasses supply {@link #index(Object)} and {@link #unIndex(Object)} to
 * decide the key an entity is stored under, and may override
 * {@link #resolveKey(Object)} to normalise keys — uppercasing a name, trimming
 * a code — so lookups match regardless of the caller's casing.</p>
 *
 * @param <Key>        the key type entries are stored under
 * @param <Value>      the type held under that key — the entity itself, or an
 *                     identifier pointing at it
 * @param <IndexValue> the entity type the index rules operate on
 */
public abstract class LocalStorage<Key, Value, IndexValue> implements Storage<Key, Value, IndexValue> {

    /**
     * How many operations pass between full expiry sweeps.
     */
    private static final int CLEANUP_THRESHOLD = 100;

    /**
     * The backing entries, keyed by resolved key.
     */
    private final ConcurrentHashMap<Key, CacheEntry<Value>> map = new ConcurrentHashMap<>();

    /**
     * Counts operations since the last sweep, wrapping at
     * {@link #CLEANUP_THRESHOLD}.
     */
    private final AtomicInteger operationsSinceCleanup = new AtomicInteger();

    /**
     * {@inheritDoc}
     *
     * <p>Invalid keys and null values are ignored. The entry's expiry is set from
     * {@link #getTTL()} at write time, and room is made first if the storage is
     * at capacity.</p>
     */
    @Override
    public void put(final Key key, final Value value) {
        this.cleanupIfNecessary();

        final Key resolvedValidKey = this.resolveValidKey(key);
        if (resolvedValidKey == null) {
            return;
        }

        if (value == null) {
            return;
        }

        this.evictIfNecessary();

        this.map.put(resolvedValidKey, CacheEntry.of(value, this.getTTL()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(final Key key) {
        this.cleanupIfNecessary();

        final Key resolvedValidKey = this.resolveValidKey(key);
        if (resolvedValidKey == null) {
            return;
        }

        this.map.remove(resolvedValidKey);
    }

    /**
     * {@inheritDoc}
     *
     * <p>An expired entry is removed as it is found, so the next read skips it
     * without waiting for a sweep.</p>
     */
    @Override
    public Optional<Value> get(final Key key) {
        this.cleanupIfNecessary();

        final Key resolvedValidKey = this.resolveValidKey(key);
        if (resolvedValidKey == null) {
            return Optional.empty();
        }

        final CacheEntry<Value> cacheEntry = this.map.get(resolvedValidKey);
        if (cacheEntry == null) {
            return Optional.empty();
        }

        if (cacheEntry.isExpired()) {
            this.map.remove(resolvedValidKey, cacheEntry);
            return Optional.empty();
        }

        return Optional.of(cacheEntry.getValue());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(final Key key) {
        this.cleanupIfNecessary();

        final Key resolvedValidKey = this.resolveValidKey(key);
        if (resolvedValidKey == null) {
            return false;
        }

        final CacheEntry<Value> cacheEntry = this.map.get(resolvedValidKey);
        if (cacheEntry == null) {
            return false;
        }

        if (cacheEntry.isExpired()) {
            this.map.remove(resolvedValidKey, cacheEntry);
            return false;
        }

        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Key> keys() {
        this.cleanupIfNecessary();

        return this.map.entrySet().stream().filter(entry -> !entry.getValue().isExpired()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<Value> values() {
        this.cleanupIfNecessary();

        return this.map.values().stream().filter(value -> !value.isExpired()).map(CacheEntry::getValue).toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long size() {
        this.cleanupIfNecessary();

        return this.map.values().stream().filter(value -> !value.isExpired()).count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        this.map.clear();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Used when the value a storage keys on changes — an account's email being
     * updated, say. Without it the entity stays reachable under the stale key
     * until the entry expires, so a lookup by the old email keeps resolving.</p>
     *
     * <p>The entity must already hold its new value; the previous key is passed
     * in because it can no longer be derived from the entity.</p>
     */
    @Override
    public void reIndex(final IndexValue indexValue, final Key previousKey) {
        this.remove(previousKey);
        this.index(indexValue);
    }

    /**
     * Returns the most entries this storage holds before it starts evicting.
     *
     * <p>Zero, the default, means unbounded — correct where the working set has a
     * natural ceiling, wrong where callers can ask for anything.</p>
     *
     * @return the entry cap, or {@code 0} for no cap
     */
    public int getMaxSize() {
        return 0;
    }

    /**
     * Removes every expired entry in one pass.
     *
     * <p>Runs automatically every {@value #CLEANUP_THRESHOLD} operations, and can
     * be called directly to reclaim memory sooner.</p>
     */
    public void cleanup() {
        this.map.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * Makes room for one more entry when the storage is at
     * {@link #getMaxSize()}.
     *
     * <p>Sweeps expired entries first, since those are free to lose. If the
     * storage is still full every entry is live, so the ones closest to expiring
     * go — which, since one time-to-live covers the whole storage, is also the
     * oldest-written ones. That is insertion order rather than
     * least-recently-used: {@link ConcurrentHashMap} does not track access
     * order, and the bookkeeping to add it would cost more than the eviction
     * quality is worth. A time-to-live is the real control over what this
     * storage holds; the cap is the backstop.</p>
     *
     * <p>A storage with no time-to-live has no age to sort on, so its evictions
     * fall in whatever order the map iterates.</p>
     *
     * <p>The check and the eviction are not atomic, so concurrent writers can
     * overshoot the cap briefly. The next write pulls it back.</p>
     */
    private void evictIfNecessary() {
        final int maxSize = this.getMaxSize();

        if (maxSize <= 0 || this.map.size() < maxSize) {
            return;
        }

        this.cleanup();

        final int excess = this.map.size() - maxSize + 1;
        if (excess <= 0) {
            return;
        }

        this.map.entrySet()
                .stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().getExpireAt()))
                .limit(excess)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this.map::remove);
    }

    /**
     * Validates a key, resolves it, then validates the result.
     *
     * <p>Checking both sides catches a {@link #resolveKey(Object)} override that
     * turns a usable key into an empty one.</p>
     *
     * @param key the key as supplied
     * @return the resolved key, or {@code null} if either form is unusable
     */
    private Key resolveValidKey(final Key key) {
        if (!this.isValidKey(key)) {
            return null;
        }

        final Key resolvedKey = this.resolveKey(key);

        return this.isValidKey(resolvedKey) ? resolvedKey : null;
    }

    /**
     * Returns whether a key is usable — non-null, and non-blank if it is a
     * string.
     *
     * @param key the key to check
     * @return {@code true} if the key can be stored or looked up
     */
    private boolean isValidKey(final Key key) {
        if (key == null) {
            return false;
        }

        return !(key instanceof final String keyString && UtilString.isEmpty(keyString));
    }

    /**
     * Runs a full sweep once every {@value #CLEANUP_THRESHOLD} operations.
     */
    private void cleanupIfNecessary() {
        if (this.operationsSinceCleanup.updateAndGet(count -> (count + 1) % CLEANUP_THRESHOLD) == 0) {
            this.cleanup();
        }
    }
}