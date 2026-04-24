package io.github.trae.database.filter.enums;

/**
 * Defines the sort direction for query results and index ordering.
 *
 * <p>Used by {@link io.github.trae.database.query.QueryOptions} for result sorting
 * and by {@link io.github.trae.database.index.Index} for index field direction.</p>
 */
public enum SortDirection {

    ASCENDING, DESCENDING
}