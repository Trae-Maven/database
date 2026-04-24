package io.github.trae.database.index.interfaces;

import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.index.Index;
import io.github.trae.database.index.IndexEntry;

import java.util.List;

public interface IIndex {

    Index unique();

    Index on(final String field, final SortDirection direction);

    List<IndexEntry> getEntries();
}