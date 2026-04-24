package io.github.trae.database.filter.interfaces;

import io.github.trae.database.filter.Filter;
import io.github.trae.database.filter.enums.FilterOperator;

import java.util.List;

public interface IFilterBuilder {

    IFilterBuilder where(final Filter filter);

    IFilterBuilder where(final String field, final FilterOperator filterOperator, final Object value);

    IFilterBuilder equals(final String field, final Object value);

    IFilterBuilder notEquals(final String field, final Object value);

    IFilterBuilder greaterThan(final String field, final Object value);

    IFilterBuilder greaterThanOrEquals(final String field, final Object value);

    IFilterBuilder lessThan(final String field, final Object value);

    IFilterBuilder lessThanOrEquals(final String field, final Object value);

    IFilterBuilder in(final String field, final Object value);

    IFilterBuilder notIn(final String field, final Object value);

    IFilterBuilder exists(final String field, final boolean exists);

    IFilterBuilder regex(final String field, final String pattern);

    List<Filter> build();
}