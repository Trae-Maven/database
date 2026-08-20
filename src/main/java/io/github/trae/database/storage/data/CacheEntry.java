package io.github.trae.database.storage.data;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * A cached value paired with the moment it stops being valid.
 *
 * <p>Expiry is stored as an absolute epoch millisecond rather than a duration, so
 * checking it is a comparison rather than arithmetic against a write time. A
 * zero expiry means the entry never expires.</p>
 *
 * <p>Entries are immutable — a refreshed value replaces the whole entry, which
 * is what keeps expiry checks free of races.</p>
 *
 * @param <Value> the cached value type
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class CacheEntry<Value> {

    /**
     * The cached value.
     */
    private final Value value;

    /**
     * Epoch milliseconds at which this entry expires, or {@code 0} to never
     * expire.
     */
    private final long expireAt;

    /**
     * Creates an entry expiring after the given duration.
     *
     * @param <Value> the cached value type
     * @param value   the value to cache
     * @param ttl     how long the entry lives, or {@code null} to never expire
     * @return the new entry
     */
    public static <Value> CacheEntry<Value> of(final Value value, final Duration ttl) {
        final long expireAt = ttl == null ? 0L : System.currentTimeMillis() + ttl.toMillis();

        return new CacheEntry<>(value, expireAt);
    }

    /**
     * Returns whether this entry has passed its expiry.
     *
     * @return {@code true} if the entry should be treated as absent
     */
    public boolean isExpired() {
        return this.expireAt > 0L && System.currentTimeMillis() >= this.expireAt;
    }
}