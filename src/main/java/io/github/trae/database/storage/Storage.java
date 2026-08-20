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
 * <p>{@link #index(Object)} and {@link #unIndex(Object)} let a subclass decide
 * which key an entity is stored under — an identifier storage keys on the id, an
 * email storage on the email — so one entity can sit in several storages at
 * once.</p>
 *
 * @param <Key>   the key type entries are stored under
 * @param <Value> the cached value type
 */
public interface Storage<Key, Value> {

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
     * @param value       the entity in its updated state
     * @param previousKey the key the entity was stored under before the change
     */
    void reIndex(final Value value, final Key previousKey);

    /**
     * Returns how long an entry lives after being written.
     *
     * @return the time-to-live, or {@code null} for entries that never expire
     */
    Duration getTTL();

    /**
     * Stores an entity under whichever key this storage is keyed by.
     *
     * @param value the entity to index
     */
    void index(final Value value);

    /**
     * Removes an entity from this storage using its current key.
     *
     * @param value the entity to remove
     */
    void unIndex(final Value value);
}