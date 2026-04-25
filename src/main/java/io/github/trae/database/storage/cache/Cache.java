package io.github.trae.database.storage.cache;

import io.github.trae.database.storage.cache.interfaces.ICache;
import io.github.trae.utilities.UtilTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * TTL-aware wrapper for cached values in {@link io.github.trae.database.storage.LocalStorage}.
 *
 * <p>Each instance captures the stored value, an optional TTL duration, and the
 * system time at construction. The {@link #isValid()} check determines whether
 * the entry has expired based on elapsed time since creation.</p>
 *
 * <p>A {@code null} TTL indicates a permanent entry that never expires.</p>
 *
 * @param <Value> the type of the cached value
 * @see ICache
 */
@AllArgsConstructor
@Getter
public class Cache<Value> implements ICache {

    private final Value value;
    private final Duration ttl;
    private final long systemTime = System.currentTimeMillis();

    /**
     * Checks whether this cache entry is still valid.
     *
     * <p>Returns {@code true} if the TTL is {@code null} (permanent entry) or
     * the elapsed time since creation has not exceeded the TTL duration.</p>
     *
     * @return {@code true} if the entry has not expired
     */
    @Override
    public boolean isValid() {
        if (this.getTtl() == null) {
            return true;
        }

        return !(UtilTime.elapsed(this.getSystemTime(), this.getTtl().toMillis()));
    }
}