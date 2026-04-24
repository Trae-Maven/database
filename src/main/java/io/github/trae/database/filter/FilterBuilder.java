package io.github.trae.database.filter;

import io.github.trae.database.filter.enums.FilterOperator;
import io.github.trae.database.filter.interfaces.IFilterBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Fluent builder for composing multiple {@link Filter} conditions.
 *
 * <p>All filters added to a builder are combined with AND semantics
 * when passed to the database driver. The builder produces an
 * unmodifiable list via {@link #build()}.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * List<Filter> filters = FilterBuilder.create()
 *         .equals("ACTIVE", true)
 *         .greaterThan("COINS", 100)
 *         .regex("EMAIL", ".*@gmail\\.com$")
 *         .build();
 * }</pre>
 *
 * @see Filter
 * @see IFilterBuilder
 */
public class FilterBuilder implements IFilterBuilder {

    private final List<Filter> filterList = new ArrayList<>();

    /**
     * Creates a new empty filter builder.
     *
     * @return a new {@link FilterBuilder} instance
     */
    public static FilterBuilder create() {
        return new FilterBuilder();
    }

    /**
     * Adds a pre-constructed {@link Filter} to the builder.
     *
     * @param filter the filter to add
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder where(final Filter filter) {
        this.filterList.add(filter);
        return this;
    }

    /**
     * Adds a filter condition from raw components.
     *
     * @param field          the field name to filter on
     * @param filterOperator the comparison operator
     * @param value          the value to compare against
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder where(final String field, final FilterOperator filterOperator, final Object value) {
        return this.where(new Filter(field, filterOperator, value));
    }

    /**
     * Adds an {@link FilterOperator#EQUALS} condition.
     *
     * @param field the field name to match
     * @param value the value to compare against
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder equals(final String field, final Object value) {
        return this.where(Filter.equals(field, value));
    }

    /**
     * Adds a {@link FilterOperator#NOT_EQUALS} condition.
     *
     * @param field the field name to match
     * @param value the value to exclude
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder notEquals(final String field, final Object value) {
        return this.where(Filter.notEquals(field, value));
    }

    /**
     * Adds a {@link FilterOperator#GREATER_THAN} condition.
     *
     * @param field the field name to compare
     * @param value the lower bound (exclusive)
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder greaterThan(final String field, final Object value) {
        return this.where(Filter.greaterThan(field, value));
    }

    /**
     * Adds a {@link FilterOperator#GREATER_THAN_OR_EQUALS} condition.
     *
     * @param field the field name to compare
     * @param value the lower bound (inclusive)
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder greaterThanOrEquals(final String field, final Object value) {
        return this.where(Filter.greaterThanOrEquals(field, value));
    }

    /**
     * Adds a {@link FilterOperator#LESS_THAN} condition.
     *
     * @param field the field name to compare
     * @param value the upper bound (exclusive)
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder lessThan(final String field, final Object value) {
        return this.where(Filter.lessThan(field, value));
    }

    /**
     * Adds a {@link FilterOperator#LESS_THAN_OR_EQUALS} condition.
     *
     * @param field the field name to compare
     * @param value the upper bound (inclusive)
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder lessThanOrEquals(final String field, final Object value) {
        return this.where(Filter.lessThanOrEquals(field, value));
    }

    /**
     * Adds an {@link FilterOperator#IN} condition for matching against a collection of values.
     *
     * @param field the field name to match
     * @param value a {@link java.util.Collection} of acceptable values
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder in(final String field, final Object value) {
        return this.where(Filter.in(field, value));
    }

    /**
     * Adds a {@link FilterOperator#NOT_IN} condition for excluding a collection of values.
     *
     * @param field the field name to match
     * @param value a {@link java.util.Collection} of values to exclude
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder notIn(final String field, final Object value) {
        return this.where(Filter.notIn(field, value));
    }

    /**
     * Adds an {@link FilterOperator#EXISTS} condition to check field presence.
     *
     * @param field  the field name to check
     * @param exists {@code true} to match documents where the field exists
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder exists(final String field, final boolean exists) {
        return this.where(Filter.exists(field, exists));
    }

    /**
     * Adds a {@link FilterOperator#REGEX} condition for pattern matching.
     *
     * @param field   the field name to match
     * @param pattern the regex pattern
     * @return this builder for chaining
     */
    @Override
    public FilterBuilder regex(final String field, final String pattern) {
        return this.where(Filter.regex(field, pattern));
    }

    /**
     * Builds and returns an unmodifiable list of all added filters.
     *
     * @return the immutable filter list
     */
    @Override
    public List<Filter> build() {
        return Collections.unmodifiableList(this.filterList);
    }
}