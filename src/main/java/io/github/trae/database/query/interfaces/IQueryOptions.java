package io.github.trae.database.query.interfaces;

import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.query.QueryOptions;

public interface IQueryOptions {

    QueryOptions sort(final String field, final SortDirection sortDirection);

    QueryOptions limit(final int limit);

    QueryOptions skip(final int skip);
}