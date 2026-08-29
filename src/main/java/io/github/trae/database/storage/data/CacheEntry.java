package io.github.trae.database.storage.data;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * A cached value paired with the moment it stops being valid.
 *
 * <p>Expiry is stored as an absolute {@link System#nanoTime()} reading rather
 * than a wall-clock one, so an NTP correction cannot keep entries alive past
 * their time-to-live or expire a whole storage at once. The comparison is
 * written as a subtraction so it stays correct across the counter's wrap.</p>
 *
 * <p>That reading doubles as the entry's age. A storage applies one time-to-live
 * to everything it holds, so {@link #expireAt} is write time plus a constant —
 * ordering by it is ordering by write time, which is what lets a bounded storage
 * evict without carrying a second timestamp. Entries with no time-to-live all
 * share the sentinel, so they carry no age at all.</p>
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
     * Sentinel {@link #expireAt} for an entry that never expires. Chosen so no
     * real {@code nanoTime} reading plus a positive duration can collide with
     * it.
     */
    private static final long NEVER_EXPIRES = Long.MIN_VALUE;

    /**
     * The cached value.
     */
    private final Value value;

    /**
     * The {@link System#nanoTime()} reading at which this entry expires, or
     * {@link #NEVER_EXPIRES}.
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
        return new CacheEntry<>(value, ttl == null ? NEVER_EXPIRES : System.nanoTime() + ttl.toNanos());
    }

    /**
     * Returns whether this entry has passed its expiry.
     *
     * @return {@code true} if the entry should be treated as absent
     */
    public boolean isExpired() {
        return this.expireAt != NEVER_EXPIRES && System.nanoTime() - this.expireAt >= 0L;
    }
}