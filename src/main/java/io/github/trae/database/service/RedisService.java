package io.github.trae.database.service;

import io.github.trae.database.driver.RedisDriver;
import io.lettuce.core.SetArgs;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Single-command convenience over a {@link RedisDriver}.
 *
 * <p>Wraps the commands an application reaches for most often, so a caller
 * writing one command with no surrounding logic does not have to go through
 * {@link RedisDriver#getResource} for it. Anything not covered here is still
 * reachable that way, against the same connection.</p>
 *
 * <p>Kept apart from the driver so the driver stays responsible for the
 * connection alone: opening it, closing it, and handing out commands. This is
 * the layer that grows as an application needs more of Redis, and it can grow
 * without the connection lifecycle growing with it.</p>
 *
 * <p>Keys are used exactly as given and no prefix is added, so anything sharing
 * a Redis with other data namespaces its own.</p>
 */
@AllArgsConstructor
public class RedisService {

    /**
     * The connection every command runs against.
     */
    private final RedisDriver redisDriver;

    /**
     * Reads a single value.
     *
     * @param key the key to read
     * @return the value, or empty if the key does not exist
     */
    public Optional<String> get(final String key) {
        return Optional.ofNullable(this.redisDriver.getResource(commands -> commands.get(key)));
    }

    /**
     * Writes a single value with an expiry.
     *
     * <p>A {@code null} time-to-live stores the entry without one, so a key that
     * should outlive the process says so by passing nothing rather than by
     * picking a large number.</p>
     *
     * @param key   the key to write
     * @param value the value to store
     * @param ttl   how long the entry lives, or {@code null} for no expiry
     */
    public void set(final String key, final String value, final Duration ttl) {
        this.redisDriver.useResource(commands -> {
            if (ttl == null) {
                commands.set(key, value);
            } else {
                commands.set(key, value, SetArgs.Builder.px(ttl.toMillis()));
            }
        });
    }

    /**
     * Writes a single value with no expiry.
     *
     * @param key   the key to write
     * @param value the value to store
     */
    public void set(final String key, final String value) {
        this.set(key, value, null);
    }

    /**
     * Deletes a key.
     *
     * @param key the key to delete
     * @return whether the key existed
     */
    public boolean delete(final String key) {
        return this.redisDriver.getResource(commands -> commands.del(key)) > 0L;
    }

    /**
     * Checks whether a key exists.
     *
     * @param key the key to check
     * @return whether the key exists
     */
    public boolean exists(final String key) {
        return this.redisDriver.getResource(commands -> commands.exists(key)) > 0L;
    }

    /**
     * Adds a member to a set.
     *
     * @param key   the set's key
     * @param value the member to add
     */
    public void addToSet(final String key, final String value) {
        this.redisDriver.useResource(commands -> commands.sadd(key, value));
    }

    /**
     * Removes a member from a set.
     *
     * @param key   the set's key
     * @param value the member to remove
     */
    public void removeFromSet(final String key, final String value) {
        this.redisDriver.useResource(commands -> commands.srem(key, value));
    }

    /**
     * Reads every member of a set.
     *
     * <p>Redis sets are unordered, so the iteration order of the returned set is
     * whatever Redis handed back rather than anything meaningful.</p>
     *
     * @param key the set's key
     * @return the members, empty if the key does not exist
     */
    public Set<String> getSet(final String key) {
        return new LinkedHashSet<>(this.redisDriver.getResource(commands -> commands.smembers(key)));
    }

    /**
     * Counts a set's members without reading them.
     *
     * @param key the set's key
     * @return the member count, zero if the key does not exist
     */
    public long getSetSize(final String key) {
        return this.redisDriver.getResource(commands -> commands.scard(key));
    }
}