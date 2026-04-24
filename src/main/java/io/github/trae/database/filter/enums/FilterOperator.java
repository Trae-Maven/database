package io.github.trae.database.filter.enums;

/**
 * Defines the comparison operators available for building database filters.
 *
 * <p>Each operator maps to a native query operation on the underlying database —
 * for example, {@link #EQUALS} becomes {@code Filters.eq()} on MongoDB and
 * {@code = ?} on MySQL.</p>
 *
 * @see io.github.trae.database.filter.Filter
 * @see io.github.trae.database.filter.FilterBuilder
 */
public enum FilterOperator {

    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUALS,
    LESS_THAN,
    LESS_THAN_OR_EQUALS,
    IN,
    NOT_IN,
    EXISTS,
    REGEX
}