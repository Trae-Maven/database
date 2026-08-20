package io.github.trae.database.batch;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * Tuning for a {@link BatchQueue}.
 *
 * <p>The defaults suit a low-latency workload — flushing every second so a crash
 * loses at most a second of writes. A service that can tolerate more delay in
 * exchange for larger, cheaper batches can raise the flush interval.</p>
 *
 * <p>The two commit thresholds are deliberately separate. A commit costs a fixed
 * fsync plus per-statement time, so judging a large chunk against a fixed
 * ceiling would warn about it simply for being large; the ceiling scales with
 * the write count instead.</p>
 */
@AllArgsConstructor
@Getter
@Setter
public class BatchQueueSettings {

    /**
     * Maximum writes committed in one transaction. A flush larger than this is
     * split across several.
     */
    private int chunkSize;

    /**
     * Delay between flushes, in milliseconds. Also the worst-case window of
     * writes lost to a crash.
     */
    private long flushIntervalMillis;

    /**
     * How long shutdown waits for the flush thread to finish before interrupting
     * it.
     */
    private long shutdownTimeoutSeconds;

    /**
     * A group of same-shape statements taking longer than this logs a warning.
     */
    private long writeWarnMillis;

    /**
     * Fixed part of the commit warning threshold, covering the cost of the commit
     * itself.
     */
    private long commitWarnBaseMillis;

    /**
     * Per-write part of the commit warning threshold, added once for each write
     * in the chunk.
     */
    private long commitWarnPerWriteMillis;

    /**
     * Creates settings with the defaults: 500 writes per chunk, a one second
     * flush interval, a five second shutdown budget, and warnings past 50ms per
     * statement group or 150ms plus 1ms per write for a commit.
     */
    public BatchQueueSettings() {
        this(
                500,
                1_000L,
                5L,
                50L,
                150L,
                1L
        );
    }
}