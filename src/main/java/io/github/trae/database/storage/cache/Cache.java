package io.github.trae.database.storage.cache;

import io.github.trae.database.storage.cache.interfaces.ICache;
import io.github.trae.utilities.UtilTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * TTL- and predicate-aware wrapper for cached values in
 * {@link io.github.trae.database.storage.LocalStorage}.
 *
 * <p>Each instance captures the stored value, an optional TTL duration, an optional
 * expiration predicate, and the system time at construction. The {@link #isValid()}
 * check determines whether the entry is still valid based on both the predicate and
 * the elapsed time since creation.</p>
 *
 * <p>A {@code null} TTL indicates an entry that never expires by time; a {@code null}
 * predicate imposes no additional expiration condition.</p>
 *
 * @param <Value> the type of the cached value
 * @see ICache
 */
@AllArgsConstructor
@Getter
public class Cache<Value> implements ICache {

    private final Value value;
    private final Duration ttl;
    private final Predicate<Value> predicate;
    private final long systemTime = System.currentTimeMillis();

    /**
     * Checks whether this cache entry is still valid.
     *
     * <p>An entry is invalid if an expiration predicate is present and tests {@code true}
     * for the stored value. Otherwise it is valid when the TTL is {@code null} (no time
     * expiry) or the elapsed time since creation has not exceeded the TTL duration.</p>
     *
     * @return {@code true} if the entry has not expired
     */
    @Override
    public boolean isValid() {
        if (this.getPredicate() != null && this.getPredicate().test(this.getValue())) {
            return false;
        }

        if (this.getTtl() == null) {
            return true;
        }

        return !(UtilTime.elapsed(this.getSystemTime(), this.getTtl().toMillis()));
    }
}