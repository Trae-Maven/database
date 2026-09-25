package io.github.trae.database.batch;

import io.github.trae.database.batch.data.PendingWrite;
import io.github.trae.database.batch.enums.OperationType;
import lombok.CustomLog;
import org.jooq.BatchBindStep;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Defers every write, coalesces writes to the same entity, and commits them in
 * batched transactions on its own thread.
 *
 * <p>Nothing reaches the database at the moment a repository saves, updates or
 * deletes. Writes land in a map keyed by table and identifier, so a burst of
 * edits to one entity collapses into a single statement rather than one per
 * call. A scheduled thread drains that map on a fixed interval.</p>
 *
 * <p>Each drain sorts by arrival order, splits into chunks, and commits each
 * chunk in one transaction. Within a transaction, runs of statements sharing
 * identical SQL are executed as a single JDBC batch — combined with pgjdbc's
 * insert rewriting, a chunk of inserts against one table becomes one round
 * trip. Sorting by sequence keeps cross-entity ordering intact, so a row is
 * never deleted before the insert that created it.</p>
 *
 * <p>A chunk that fails is logged and skipped; the rest of the flush continues.
 * Writes in a failed chunk are lost, having already left the map.</p>
 *
 * <p>{@link #shutdown()} stops the scheduler and performs one final drain, and is
 * registered as a JVM shutdown hook so an abrupt exit still flushes.</p>
 *
 * @see PendingWrite
 * @see BatchQueueSettings
 */
@CustomLog
public class BatchQueue {

    /**
     * The context every commit runs through.
     */
    private final DSLContext dslContext;

    /**
     * Pending writes by {@code table:id}. One entry per entity, merged in place.
     */
    private final ConcurrentHashMap<String, PendingWrite> pendingWriteMap = new ConcurrentHashMap<>();

    /**
     * Stamps arrival order onto each newly queued entity.
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Set once shutdown begins, rejecting further writes and making a second
     * shutdown a no-op.
     */
    private final AtomicBoolean shutdown = new AtomicBoolean();

    /**
     * Serialises flushes, so a manual {@link #flush()} and the scheduled drain
     * cannot commit concurrently.
     */
    private final Object flushLock = new Object();

    /**
     * The single daemon thread running scheduled drains.
     */
    private final ScheduledExecutorService scheduledExecutorService;

    /**
     * Interval, chunk size and warning thresholds.
     */
    private final BatchQueueSettings batchQueueSettings;

    /**
     * Starts the flush scheduler and registers the shutdown hook.
     *
     * @param dslContext         the context to commit through
     * @param batchQueueSettings the tuning to apply
     * @throws IllegalArgumentException if the flush interval or chunk size is
     *                                  below one
     */
    public BatchQueue(final DSLContext dslContext, final BatchQueueSettings batchQueueSettings) {
        if (batchQueueSettings.getFlushIntervalMillis() < 1L) {
            throw new IllegalArgumentException("Flush interval must be at least 1ms.");
        }

        if (batchQueueSettings.getChunkSize() < 1) {
            throw new IllegalArgumentException("Chunk size must be at least 1.");
        }

        this.dslContext = dslContext;
        this.batchQueueSettings = batchQueueSettings;

        this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "batch-queue");
            thread.setDaemon(true);
            return thread;
        });

        this.scheduledExecutorService.scheduleWithFixedDelay(this::drain, batchQueueSettings.getFlushIntervalMillis(), batchQueueSettings.getFlushIntervalMillis(), TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "batch-queue-shutdown"));
    }

    /**
     * Returns whether this queue has been shut down.
     *
     * @return {@code true} once shutdown has begun
     */
    public boolean isShutdown() {
        return this.shutdown.get();
    }

    /**
     * Returns how many entities currently have a write waiting.
     *
     * <p>Counts entities rather than calls, since writes to one entity are merged
     * into a single pending entry.</p>
     *
     * @return the pending entity count
     */
    public int getPendingWriteCount() {
        return this.pendingWriteMap.size();
    }

    /**
     * Queues a write, merging it into any pending write for the same entity.
     *
     * @param table           the table being written to
     * @param identifierField the identifier column
     * @param identifierValue the entity's identifier
     * @param valueMap        the columns to write, empty for a delete
     * @param operationType   the kind of statement to render
     * @throws IllegalStateException if the queue has been shut down
     */
    public void queue(final String table, final Field<UUID> identifierField, final UUID identifierValue, final Map<Field<?>, Object> valueMap, final OperationType operationType) {
        if (this.shutdown.get()) {
            throw new IllegalStateException("BatchQueue has been shut down.");
        }

        final String key = "%s:%s".formatted(table, identifierValue);

        this.pendingWriteMap.merge(key, new PendingWrite(table, key, this.sequence.incrementAndGet(), identifierField, identifierValue, valueMap, operationType), PendingWrite::merge);
    }

    /**
     * Commits every pending write, in arrival order, in chunked transactions.
     *
     * <p>Claims writes by removing them from the map, so a write queued mid-flush
     * is left for the next one rather than lost or committed twice. A chunk that
     * throws is logged and skipped; later chunks still commit.</p>
     */
    public void flush() {
        if (this.pendingWriteMap.isEmpty()) {
            return;
        }

        synchronized (this.flushLock) {
            final List<PendingWrite> pendingWriteList = this.pendingWriteMap.values().stream()
                    .filter(pendingWrite -> this.pendingWriteMap.remove(pendingWrite.getKey(), pendingWrite))
                    .sorted(Comparator.comparingLong(PendingWrite::getSequence))
                    .toList();

            for (int index = 0; index < pendingWriteList.size(); index += this.batchQueueSettings.getChunkSize()) {
                final List<PendingWrite> chunk = pendingWriteList.subList(index, Math.min(index + this.batchQueueSettings.getChunkSize(), pendingWriteList.size()));

                try {
                    this.commit(chunk);
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to commit {} write(s), starting at [{}].", chunk.size(), chunk.getFirst().getKey(), throwable);
                }
            }
        }
    }

    /**
     * Runs a flush, swallowing anything thrown.
     *
     * <p>An exception escaping a scheduled task cancels all future runs, which
     * would silently stop the queue forever — hence catching {@link Throwable}
     * rather than letting it propagate.</p>
     */
    private void drain() {
        try {
            this.flush();
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to flush the batch queue.", throwable);
        }
    }

    /**
     * Commits one chunk in a single transaction, batching runs of identical SQL.
     *
     * <p>Statements are rendered with indexed parameters so two writes of the same
     * shape produce the same SQL string regardless of their values, which is what
     * makes them groupable.</p>
     *
     * @param pendingWriteList the writes to commit, in arrival order
     */
    private void commit(final List<PendingWrite> pendingWriteList) {
        final long start = System.nanoTime();

        this.dslContext.transaction(configuration -> {
            final DSLContext transactionalDslContext = DSL.using(configuration);
            final List<Query> queryList = pendingWriteList.stream().map(pendingWrite -> pendingWrite.toQuery(transactionalDslContext)).toList();
            final List<String> sqlList = queryList.stream().map(query -> query.getSQL(ParamType.INDEXED)).toList();

            int index = 0;

            while (index < queryList.size()) {
                int end = index + 1;

                while (end < queryList.size() && sqlList.get(end).equals(sqlList.get(index))) {
                    end++;
                }

                this.execute(transactionalDslContext, queryList.subList(index, end), pendingWriteList.get(index).getTable());

                index = end;
            }
        });

        final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        if (duration > this.batchQueueSettings.getCommitWarnBaseMillis() + (pendingWriteList.size() * this.batchQueueSettings.getCommitWarnPerWriteMillis())) {
            LOGGER.warn("Took {}ms to commit {} write(s).", duration, pendingWriteList.size());
        }
    }

    /**
     * Executes a run of statements sharing one SQL string.
     *
     * <p>A single statement executes directly; several are bound onto one
     * prepared statement and sent as a JDBC batch.</p>
     *
     * @param dslContext the transactional context
     * @param queryList  the statements, all of identical shape
     * @param table      the table being written, for the slow-write warning
     */
    private void execute(final DSLContext dslContext, final List<Query> queryList, final String table) {
        final long start = System.nanoTime();

        if (queryList.size() == 1) {
            queryList.getFirst().execute();
        } else {
            BatchBindStep batchBindStep = dslContext.batch(queryList.getFirst());

            for (final Query query : queryList) {
                batchBindStep = batchBindStep.bind(query.getBindValues().toArray());
            }

            batchBindStep.execute();
        }

        final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        if (duration > this.batchQueueSettings.getWriteWarnMillis()) {
            LOGGER.warn("Took {}ms to execute {} statement(s) against [{}].", duration, queryList.size(), table);
        }
    }

    /**
     * Stops accepting writes, waits for the scheduler to stop, then drains what
     * remains.
     *
     * <p>Safe to call more than once — the first call wins and the rest return
     * immediately, which is what lets both an explicit shutdown and the JVM hook
     * call it.</p>
     */
    public void shutdown() {
        if (!this.shutdown.compareAndSet(false, true)) {
            return;
        }

        this.scheduledExecutorService.shutdown();

        try {
            if (!this.scheduledExecutorService.awaitTermination(this.batchQueueSettings.getShutdownTimeoutSeconds(), TimeUnit.SECONDS)) {
                this.scheduledExecutorService.shutdownNow();
            }
        } catch (final InterruptedException e) {
            this.scheduledExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        this.drain();
    }
}