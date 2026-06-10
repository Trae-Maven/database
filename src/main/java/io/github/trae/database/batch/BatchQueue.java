package io.github.trae.database.batch;

import io.github.trae.database.batch.interfaces.IBatchQueue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generic asynchronous batch queue for grouping and flushing operations.
 *
 * <p>Collects items of type {@code T} and periodically flushes them as a single
 * batch to the provided {@link Consumer}. This enables bulk execution — for example,
 * the MongoDB driver collects {@code WriteModel} instances and flushes them as a
 * single {@code bulkWrite} call.</p>
 *
 * <p>Supports two modes based on the {@code period} parameter:</p>
 * <ul>
 *     <li><strong>Instant</strong> ({@link Duration#ZERO}) — flushes immediately on every {@link #add}</li>
 *     <li><strong>Batched</strong> — flushes when the queue reaches {@code batchSize} or on the scheduled interval</li>
 * </ul>
 *
 * <p>Thread safety is provided by a {@link ReentrantLock} guarding all queue access.
 * Flush execution is dispatched to a single-threaded executor with a daemon thread,
 * so batches are processed strictly in the order they are drained — batch N completes
 * before batch N+1 begins. This preserves write ordering for non-commutative operations
 * on the same document across separate batches, and provides natural backpressure under
 * sustained load.</p>
 *
 * <p>On {@link #shutdown()}, the scheduler is stopped and awaited, remaining items are
 * flushed synchronously on the calling thread, and the executor awaits termination for
 * up to 30 seconds before forcing shutdown.</p>
 *
 * @param <T> the type of operation to batch
 * @see IBatchQueue
 */
public class BatchQueue<T> implements IBatchQueue<T> {

    private static final Logger LOGGER = Logger.getLogger(BatchQueue.class.getName());

    private final List<T> queue = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final Consumer<List<T>> flushConsumer;
    private final ExecutorService executorService;
    private final int batchSize;
    private final boolean instant;

    private ScheduledExecutorService scheduledExecutorService;

    /**
     * Creates a new batch queue.
     *
     * @param batchSize     the maximum number of items before an automatic flush is triggered
     * @param period        the flush interval; {@link Duration#ZERO} for instant mode
     * @param flushConsumer the consumer that receives the entire batch for processing
     */
    public BatchQueue(final int batchSize, final Duration period, final Consumer<List<T>> flushConsumer) {
        this.batchSize = batchSize;
        this.instant = period.isZero();
        this.flushConsumer = flushConsumer;

        this.executorService = Executors.newSingleThreadExecutor(
                runnable -> {
                    final Thread thread = new Thread(runnable, "batch-queue-worker");
                    thread.setDaemon(true);
                    return thread;
                }
        );

        if (!(this.instant)) {
            this.scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "batch-queue-scheduler");
                thread.setDaemon(true);
                return thread;
            });

            this.scheduledExecutorService.scheduleAtFixedRate(this::flush, period.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Adds an operation to the queue.
     *
     * <p>In instant mode, triggers an immediate flush. In batched mode,
     * triggers a flush when the queue size reaches the configured batch size.
     * Rejects operations after {@link #shutdown()} has been called.</p>
     *
     * @param operation the operation to enqueue
     */
    @Override
    public void add(final T operation) {
        if (this.shutdown.get()) {
            LOGGER.warning("BatchQueue is shut down, rejecting operation");
            return;
        }

        this.lock.lock();
        try {
            this.queue.add(operation);

            if (this.instant || this.queue.size() >= this.batchSize) {
                this.drainAndExecute();
            }
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Manually triggers a flush of all currently queued operations.
     *
     * <p>The batch is drained and dispatched to the executor for async processing.
     * Also called automatically by the scheduled executor in batched mode. Has no
     * effect once {@link #shutdown()} has been called, since shutdown performs its
     * own final flush and the executor is no longer accepting tasks.</p>
     */
    @Override
    public void flush() {
        if (this.shutdown.get()) {
            return;
        }

        this.lock.lock();
        try {
            this.drainAndExecute();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Gracefully shuts down the batch queue.
     *
     * <p>Execution order:</p>
     * <ol>
     *     <li>Stops the scheduled executor and awaits its termination, so no scheduled
     *     flush can run concurrently with the final flush below</li>
     *     <li>Drains remaining items and flushes them synchronously on the calling thread</li>
     *     <li>Shuts down the worker executor and awaits termination for up to 30 seconds</li>
     *     <li>Forces shutdown if termination times out</li>
     * </ol>
     *
     * <p>Idempotent — subsequent calls are no-ops.</p>
     */
    @Override
    public void shutdown() {
        if (!(this.shutdown.compareAndSet(false, true))) {
            return;
        }

        if (this.scheduledExecutorService != null) {
            this.scheduledExecutorService.shutdown();

            try {
                if (!(this.scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS))) {
                    this.scheduledExecutorService.shutdownNow();
                }
            } catch (final InterruptedException e) {
                this.scheduledExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Final synchronous flush on calling thread
        this.lock.lock();
        try {
            if (!(this.queue.isEmpty())) {
                final List<T> remaining = new ArrayList<>(this.queue);
                this.queue.clear();

                try {
                    this.flushConsumer.accept(remaining);
                } catch (final Exception e) {
                    LOGGER.log(Level.SEVERE, "BatchQueue final flush failed", e);
                }
            }
        } finally {
            this.lock.unlock();
        }

        this.executorService.shutdown();

        try {
            if (!(this.executorService.awaitTermination(30, TimeUnit.SECONDS))) {
                final List<Runnable> dropped = this.executorService.shutdownNow();

                if (!(dropped.isEmpty())) {
                    LOGGER.warning("BatchQueue forced shutdown, dropped " + dropped.size() + " pending tasks");
                }
            }
        } catch (final InterruptedException e) {
            this.executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns the number of operations currently waiting in the queue.
     *
     * @return the pending operation count
     */
    @Override
    public int pending() {
        this.lock.lock();
        try {
            return this.queue.size();
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Drains the queue into a local list and dispatches it to the executor.
     *
     * <p>Must be called while holding {@link #lock}. The batch is submitted to the
     * single-threaded executor, so batches run strictly in drain order. A failure
     * within {@link #flushConsumer} is logged and does not affect later batches.</p>
     *
     * <p>If the executor rejects the task — which can only happen during the narrow
     * window of {@link #shutdown()} — the batch is executed inline on the calling
     * thread so its operations are not lost.</p>
     */
    private void drainAndExecute() {
        if (this.queue.isEmpty()) {
            return;
        }

        final List<T> operations = new ArrayList<>(this.queue);
        this.queue.clear();

        try {
            this.executorService.execute(() -> {
                try {
                    this.flushConsumer.accept(operations);
                } catch (final Exception e) {
                    LOGGER.log(Level.SEVERE, "BatchQueue flush failed", e);
                }
            });
        } catch (final RejectedExecutionException e) {
            try {
                this.flushConsumer.accept(operations);
            } catch (final Exception inner) {
                LOGGER.log(Level.SEVERE, "BatchQueue inline flush failed", inner);
            }
        }
    }
}