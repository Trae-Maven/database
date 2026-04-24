package io.github.trae.database.index;

import io.github.trae.database.filter.enums.SortDirection;
import io.github.trae.database.index.interfaces.IIndex;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines a database index with one or more field entries.
 *
 * <p>Supports single-field and compound indexes with configurable sort direction
 * and uniqueness. The database driver translates this into its native index
 * format (e.g. {@code Indexes.compoundIndex()} for MongoDB,
 * {@code CREATE INDEX} for MySQL).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Single unique index
 * new Index().on("EMAIL", SortDirection.ASCENDING).unique();
 *
 * // Compound index
 * new Index()
 *         .on("ACTIVE", SortDirection.ASCENDING)
 *         .on("LISTED", SortDirection.ASCENDING)
 *         .on("LAST_BUMPED_AT", SortDirection.DESCENDING);
 * }</pre>
 *
 * @see IndexEntry
 * @see io.github.trae.database.driver.DatabaseDriver#createIndex
 */
@Getter
public class Index implements IIndex {

    private final List<IndexEntry> entryList = new ArrayList<>();

    private boolean unique;

    /**
     * Marks this index as unique, enforcing that no two documents
     * or rows can have the same value(s) for the indexed field(s).
     *
     * @return this index instance for chaining
     */
    @Override
    public Index unique() {
        this.unique = true;
        return this;
    }

    /**
     * Adds a field to this index with the specified sort direction.
     *
     * <p>For compound indexes, call this method multiple times. Field order
     * matters — it determines the index key ordering.</p>
     *
     * @param field     the field name to index
     * @param direction the sort direction for this field
     * @return this index instance for chaining
     */
    @Override
    public Index on(final String field, final SortDirection direction) {
        this.entryList.add(new IndexEntry(field, direction));
        return this;
    }

    /**
     * Returns an unmodifiable view of the index entries.
     *
     * @return the list of {@link IndexEntry} instances defining this index
     */
    @Override
    public List<IndexEntry> getEntries() {
        return Collections.unmodifiableList(this.entryList);
    }
}