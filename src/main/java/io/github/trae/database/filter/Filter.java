package io.github.trae.database.filter;

import io.github.trae.database.filter.enums.FilterOperator;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A single filter condition used to query the database.
 *
 * <p>Composed of a field name, an {@link FilterOperator operator}, and a value.
 * The database driver translates each filter into a native query condition
 * (e.g. {@code Filters.eq()} for MongoDB, {@code WHERE field = ?} for MySQL).</p>
 *
 * <p>Static factory methods are provided for concise filter construction:</p>
 * <pre>{@code
 * Filter.equals("USERNAME", "Trae")
 * Filter.greaterThan("COINS", 10_000)
 * Filter.in("STATUS", List.of("ACTIVE", "PENDING"))
 * Filter.regex("EMAIL", ".*@gmail\\.com$")
 * }</pre>
 *
 * <p>For chaining multiple filters, use {@link FilterBuilder}.</p>
 *
 * @see FilterBuilder
 * @see FilterOperator
 */
@RequiredArgsConstructor
@Getter
public class Filter {

    private final String field;
    private final FilterOperator operator;
    private final Object value;

    /**
     * Creates an {@link FilterOperator#EQUALS} filter.
     *
     * @param field the field name to match
     * @param value the value to compare against
     * @return the filter instance
     */
    public static Filter equals(final String field, final Object value) {
        return new Filter(field, FilterOperator.EQUALS, value);
    }

    /**
     * Creates a {@link FilterOperator#NOT_EQUALS} filter.
     *
     * @param field the field name to match
     * @param value the value to exclude
     * @return the filter instance
     */
    public static Filter notEquals(final String field, final Object value) {
        return new Filter(field, FilterOperator.NOT_EQUALS, value);
    }

    /**
     * Creates a {@link FilterOperator#GREATER_THAN} filter.
     *
     * @param field the field name to compare
     * @param value the lower bound (exclusive)
     * @return the filter instance
     */
    public static Filter greaterThan(final String field, final Object value) {
        return new Filter(field, FilterOperator.GREATER_THAN, value);
    }

    /**
     * Creates a {@link FilterOperator#GREATER_THAN_OR_EQUALS} filter.
     *
     * @param field the field name to compare
     * @param value the lower bound (inclusive)
     * @return the filter instance
     */
    public static Filter greaterThanOrEquals(final String field, final Object value) {
        return new Filter(field, FilterOperator.GREATER_THAN_OR_EQUALS, value);
    }

    /**
     * Creates a {@link FilterOperator#LESS_THAN} filter.
     *
     * @param field the field name to compare
     * @param value the upper bound (exclusive)
     * @return the filter instance
     */
    public static Filter lessThan(final String field, final Object value) {
        return new Filter(field, FilterOperator.LESS_THAN, value);
    }

    /**
     * Creates a {@link FilterOperator#LESS_THAN_OR_EQUALS} filter.
     *
     * @param field the field name to compare
     * @param value the upper bound (inclusive)
     * @return the filter instance
     */
    public static Filter lessThanOrEquals(final String field, final Object value) {
        return new Filter(field, FilterOperator.LESS_THAN_OR_EQUALS, value);
    }

    /**
     * Creates an {@link FilterOperator#IN} filter for matching against a collection of values.
     *
     * @param field the field name to match
     * @param value a {@link java.util.Collection} of acceptable values
     * @return the filter instance
     */
    public static Filter in(final String field, final Object value) {
        return new Filter(field, FilterOperator.IN, value);
    }

    /**
     * Creates a {@link FilterOperator#NOT_IN} filter for excluding a collection of values.
     *
     * @param field the field name to match
     * @param value a {@link java.util.Collection} of values to exclude
     * @return the filter instance
     */
    public static Filter notIn(final String field, final Object value) {
        return new Filter(field, FilterOperator.NOT_IN, value);
    }

    /**
     * Creates an {@link FilterOperator#EXISTS} filter to check field presence.
     *
     * <p>On MongoDB this maps to {@code $exists}. On MySQL it maps to
     * {@code IS [NOT] NULL}.</p>
     *
     * @param field  the field name to check
     * @param exists {@code true} to match documents where the field exists
     * @return the filter instance
     */
    public static Filter exists(final String field, final boolean exists) {
        return new Filter(field, FilterOperator.EXISTS, exists);
    }

    /**
     * Creates a {@link FilterOperator#REGEX} filter for pattern matching.
     *
     * <p>On MongoDB this maps to {@code $regex}. On MySQL it maps to {@code REGEXP}.</p>
     *
     * @param field   the field name to match
     * @param pattern the regex pattern
     * @return the filter instance
     */
    public static Filter regex(final String field, final String pattern) {
        return new Filter(field, FilterOperator.REGEX, pattern);
    }
}