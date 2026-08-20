package io.github.trae.database.repository.enums;

/**
 * The kind of index to create for a property.
 *
 * <p>Declared per property through
 * {@link io.github.trae.database.repository.EntityRepository#getIndexes()} and
 * applied during startup.</p>
 */
public enum IndexType {

    /**
     * A standard B-tree index. Serves equality, ranges and ordering, and is the
     * right choice for almost every column — identifiers, foreign keys, statuses
     * and timestamps that pages sort by.
     */
    BTREE,

    /**
     * A GIN index using trigram operators, for substring and similarity matching
     * on text — the kind of search a {@code LIKE '%term%'} performs, which a
     * B-tree cannot serve. Requires the {@code pg_trgm} extension, installed by
     * {@link io.github.trae.database.driver.DatabaseDriver#connect()}.
     */
    GIN_TRGM,

    /**
     * A BRIN index, storing per-block ranges rather than per-row entries. Tiny,
     * but only useful for range scans on a large append-only table whose values
     * correlate with physical order. Cannot serve an ordering, so a column that
     * pages sort by wants {@link #BTREE} instead.
     */
    BRIN
}