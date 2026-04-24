package io.github.trae.database.query;

import io.github.trae.database.filter.Filter;
import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.query.interfaces.IQueryOptions;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Encapsulates query parameters for database find operations beyond simple filters.
 *
 * <p>Wraps a filter list with optional sort, limit, and skip controls.
 * Used by both {@code findOneSynchronously} and {@code findManySynchronously}
 * overloads on the {@link io.github.trae.database.driver.DatabaseDriver}.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * QueryOptions.of(filters)
 *         .sort("CREATED_AT", SortDirection.DESCENDING)
 *         .limit(10)
 *         .skip(20);
 *
 * QueryOptions.empty()
 *         .sort("USERNAME", SortDirection.ASCENDING)
 *         .limit(50);
 * }</pre>
 *
 * @see io.github.trae.database.filter.Filter
 * @see io.github.trae.database.driver.DatabaseDriver
 */
@RequiredArgsConstructor
@Getter
public class QueryOptions implements IQueryOptions {

    /**
     * The filter conditions for this query. Combined with AND semantics.
     */
    private final List<Filter> filters;

    /**
     * The field to sort results by, or {@code null} for default ordering.
     */
    private String field;

    /**
     * The sort direction. Defaults to {@link SortDirection#ASCENDING}.
     */
    private SortDirection sortDirection = SortDirection.ASCENDING;

    /**
     * The maximum number of results to return. {@code -1} for no limit.
     */
    private int limit = -1;

    /**
     * The number of results to skip before returning. {@code 0} for no skip.
     */
    private int skip = 0;

    /**
     * Creates query options wrapping the given filter list.
     *
     * @param filterList the filters to apply
     * @return a new {@link QueryOptions} instance
     */
    public static QueryOptions of(final List<Filter> filterList) {
        return new QueryOptions(filterList);
    }

    /**
     * Creates query options with no filters.
     *
     * @return a new {@link QueryOptions} instance with an empty filter list
     */
    public static QueryOptions empty() {
        return new QueryOptions(Collections.emptyList());
    }

    /**
     * Sets the sort field and direction for result ordering.
     *
     * @param field         the field name to sort by
     * @param sortDirection the sort direction
     * @return this instance for chaining
     */
    @Override
    public QueryOptions sort(final String field, final SortDirection sortDirection) {
        this.field = field;
        this.sortDirection = sortDirection;
        return this;
    }

    /**
     * Sets the maximum number of results to return.
     *
     * @param limit the result limit, or {@code -1} for unlimited
     * @return this instance for chaining
     */
    @Override
    public QueryOptions limit(final int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Sets the number of results to skip before returning.
     *
     * @param skip the number of results to skip
     * @return this instance for chaining
     */
    @Override
    public QueryOptions skip(final int skip) {
        this.skip = skip;
        return this;
    }
}