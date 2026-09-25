package io.github.trae.database.storage.data;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * A cached value paired with the moment it stops being valid and whether it is
 * pinned in its local storage.
 *
 * <p>Expiry is stored as an absolute {@link System#nanoTime()} reading rather
 * than a wall-clock one, so an NTP correction cannot keep entries alive past
 * their time-to-live or expire a whole storage at once. The comparison is
 * written as a subtraction so it stays correct across the counter's wrap.</p>
 *
 * <p>A pinned entry remains valid regardless of its expiry and must be explicitly
 * unpinned before normal expiry or eviction can remove it. Replacing the entry's
 * value does not inherently change that state.</p>
 *
 * <p>Entries are immutable — refreshing, pinning or unpinning creates a
 * replacement entry, keeping state changes atomic when installed through a
 * concurrent map.</p>
 *
 * @param <Value> the cached value type
 */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Getter
public class CacheEntry<Value> {

    /**
     * Sentinel {@link #expireAt} for an entry that never expires.
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
     * Whether this entry is protected from expiry and eviction.
     */
    private final boolean pinned;

    /**
     * Creates an unpinned entry expiring after the given duration.
     *
     * @param <Value> the cached value type
     * @param value   the value to cache
     * @param ttl     how long the entry lives, or {@code null} to never expire
     * @return the new entry
     */
    public static <Value> CacheEntry<Value> of(final Value value, final Duration ttl) {
        return CacheEntry.of(value, ttl, false);
    }

    /**
     * Creates an entry with the requested pinned state.
     *
     * @param <Value> the cached value type
     * @param value   the value to cache
     * @param ttl     how long the entry lives, or {@code null} to never expire
     * @param pinned  whether the entry is protected from expiry and eviction
     * @return the new entry
     */
    public static <Value> CacheEntry<Value> of(final Value value, final Duration ttl, final boolean pinned) {
        return new CacheEntry<>(value, ttl == null ? NEVER_EXPIRES : System.nanoTime() + ttl.toNanos(), pinned);
    }

    /**
     * Returns a pinned copy of this entry.
     *
     * @return the pinned entry
     */
    public CacheEntry<Value> pin() {
        if (this.pinned) {
            return this;
        }

        return new CacheEntry<>(this.value, this.expireAt, true);
    }

    /**
     * Returns an unpinned copy whose time-to-live starts from now.
     *
     * @param ttl how long the entry should remain after being unpinned, or
     *            {@code null} to never expire
     * @return the unpinned entry
     */
    public CacheEntry<Value> unpin(final Duration ttl) {
        return CacheEntry.of(this.value, ttl, false);
    }

    /**
     * Returns whether this entry has passed its expiry.
     *
     * <p>A pinned entry never reports itself as expired.</p>
     *
     * @return {@code true} if the entry should be treated as absent
     */
    public boolean isExpired() {
        return !this.pinned && this.expireAt != NEVER_EXPIRES && System.nanoTime() - this.expireAt >= 0L;
    }
}