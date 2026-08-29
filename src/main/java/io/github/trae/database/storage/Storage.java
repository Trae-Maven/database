package io.github.trae.database.storage;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Common surface for the cache tiers sitting in front of the database.
 *
 * <p>Two implementations exist: {@link LocalStorage}, backed by an in-process
 * map, and {@link RedisStorage}, shared across every server on a network. Both
 * apply a time-to-live from {@link #getTTL()} and treat expired entries as
 * absent.</p>
 *
 * <p>What a storage holds and what it is keyed by are separate from the entity
 * it belongs to, which is why there are three type parameters. A primary storage
 * keys an entity on its identifier and stores the entity itself, so {@code Value}
 * and {@code IndexValue} coincide. A secondary storage keys on something mutable
 * — an email, a username — and stores only the identifier, leaving one copy of
 * the entity in the primary storage for every lookup path to share. There
 * {@code Value} is the identifier and {@code IndexValue} is still the entity,
 * since {@link #index(Object)} needs the whole thing to derive both sides of the
 * mapping.</p>
 *
 * <p>{@link #index(Object)} and {@link #unIndex(Object)} are where that
 * derivation lives: given an entity, a subclass decides the key it belongs under
 * and the value to store there, so one entity can be reachable through several
 * storages at once.</p>
 *
 * <p>{@link #resolveKey(Object)} normalises keys on both sides of every
 * operation, so a storage keyed on something case-insensitive must override it
 * rather than relying on callers to pass a consistent form.</p>
 *
 * @param <Key>        the key type entries are stored under
 * @param <Value>      the type held under that key — the entity itself, or an
 *                     identifier pointing at it
 * @param <IndexValue> the entity type the index rules operate on
 */
public interface Storage<Key, Value, IndexValue> {

    /**
     * Stores a value under the given key, replacing any existing entry and
     * applying the storage's time-to-live.
     *
     * <p>Null keys and values are ignored rather than stored.</p>
     *
     * @param key   the key to store under
     * @param value the value to store
     */
    void put(final Key key, final Value value);

    /**
     * Removes the entry stored under the given key, if any.
     *
     * @param key the key to remove
     */
    void remove(final Key key);

    /**
     * Returns the value stored under the given key, if present and not expired.
     *
     * @param key the key to look up
     * @return the cached value, or empty if absent or expired
     */
    Optional<Value> get(final Key key);

    /**
     * Returns whether a live entry exists under the given key.
     *
     * @param key the key to check
     * @return {@code true} if an unexpired entry exists
     */
    boolean contains(final Key key);

    /**
     * Returns the keys of every live entry.
     *
     * @return the live key set
     */
    Set<Key> keys();

    /**
     * Returns the values of every live entry.
     *
     * @return the live values
     */
    List<Value> values();

    /**
     * Returns the number of live entries.
     *
     * @return the live entry count
     */
    long size();

    /**
     * Removes every entry from this storage.
     */
    void clear();

    /**
     * Moves an entity from an old key to its current one.
     *
     * @param indexValue  the entity in its updated state
     * @param previousKey the key it was stored under before the change
     */
    void reIndex(final IndexValue indexValue, final Key previousKey);

    /**
     * Returns how long an entry lives after being written.
     *
     * @return the time-to-live, or {@code null} for entries that never expire
     */
    Duration getTTL();

    /**
     * Normalises a key before it is stored or looked up.
     *
     * <p>Returns the key unchanged by default. Override to make lookups
     * case-insensitive or otherwise forgiving — applied on writes as well as
     * reads, so an entity indexed under its raw form is still found by a caller
     * using a different casing.</p>
     *
     * @param key the key as supplied
     * @return the key to actually store or look up under
     */
    default Key resolveKey(final Key key) {
        return key;
    }

    /**
     * Stores an entity under whichever key this storage is keyed by, deriving
     * both the key and the stored value from it.
     *
     * @param indexValue the entity to index
     */
    void index(final IndexValue indexValue);

    /**
     * Removes an entity from this storage using its current key.
     *
     * @param indexValue the entity to remove
     */
    void unIndex(final IndexValue indexValue);
}