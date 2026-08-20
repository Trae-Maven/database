package io.github.trae.database.driver;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.trae.database.batch.BatchQueue;
import io.github.trae.database.batch.BatchQueueSettings;
import io.github.trae.database.repository.EntityRepository;
import lombok.CustomLog;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;

/**
 * Owns the PostgreSQL connection pool, the jOOQ context built over it, and the
 * batch queue every write passes through.
 *
 * <p>Repositories add themselves to {@code getRepositoryList()} as they are
 * constructed, so the driver knows the full schema before it opens anything.
 * {@link #connect()} then does the whole startup sequence in order: open the
 * pool, build the context and queue, install the {@code pg_trgm} extension, and
 * bring every registered table and index up to date. Repositories are therefore
 * constructed first and {@link #connect()} called afterwards.</p>
 *
 * <p>Two pgjdbc properties are set unconditionally.
 * {@code stringtype=unspecified} lets Postgres coerce string binds into
 * {@code jsonb} columns rather than rejecting them, and
 * {@code reWriteBatchedInserts=true} lets the driver fold a batch of identical
 * inserts into one multi-row statement — which is exactly the shape
 * {@link BatchQueue} produces.</p>
 *
 * <p>Abstract so a consumer subclasses it and annotates the subclass for their
 * own dependency injection framework, keeping the library itself free of any
 * framework's annotations.</p>
 *
 * @see BatchQueue
 * @see EntityRepository
 */
@CustomLog
@RequiredArgsConstructor
public abstract class DatabaseDriver implements Connector {

    /**
     * Every repository built against this driver, in construction order.
     * Populated by {@link EntityRepository}'s constructor.
     */
    @Getter
    private final List<EntityRepository<?>> repositoryList = new ArrayList<>();

    /**
     * Pool configuration, supplied by the consumer.
     */
    private final HikariConfig hikariConfig;

    /**
     * Tuning for the batch queue created during {@link #connect()}.
     */
    private final BatchQueueSettings batchQueueSettings;

    /**
     * The connection pool, opened on {@link #connect()}.
     */
    private HikariDataSource dataSource;

    /**
     * The jOOQ context every read runs through.
     */
    @Getter
    private DSLContext dslContext;

    /**
     * The queue every write is deferred into.
     */
    @Getter
    private BatchQueue batchQueue;

    /**
     * Opens the pool and brings the schema up to date.
     *
     * <p>Runs, in order: pgjdbc property setup, pool creation, jOOQ context and
     * batch queue creation, {@code pg_trgm} installation, then
     * {@link EntityRepository#createTable()},
     * {@link EntityRepository#migrateSchema()} and
     * {@link EntityRepository#createIndexes()} across every registered
     * repository.</p>
     *
     * <p>The extension is installed before any index work because a
     * {@link io.github.trae.database.repository.enums.IndexType#GIN_TRGM} index
     * cannot be created without it.</p>
     */
    @Override
    public void connect() {
        this.hikariConfig.addDataSourceProperty("stringtype", "unspecified");
        this.hikariConfig.addDataSourceProperty("reWriteBatchedInserts", "true");

        this.dataSource = new HikariDataSource(this.hikariConfig);

        this.dslContext = DSL.using(this.dataSource, SQLDialect.POSTGRES, new Settings().withExecuteLogging(false).withRenderSchema(false));
        this.batchQueue = new BatchQueue(this.dslContext, this.batchQueueSettings);

        this.dslContext.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");

        for (final EntityRepository<?> entityRepository : this.repositoryList) {
            entityRepository.createTable();
            entityRepository.migrateSchema();
            entityRepository.createIndexes();
        }
    }

    /**
     * Drains pending writes, then closes the pool.
     *
     * <p>Order matters — shutting the queue down first gives it a live pool to
     * commit its final drain through. Closing the pool first would lose every
     * queued write.</p>
     */
    @Override
    public void disconnect() {
        if (this.batchQueue != null) {
            this.batchQueue.shutdown();
        }

        if (this.dataSource != null) {
            this.dataSource.close();
        }
    }
}