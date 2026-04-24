package io.github.trae.database.index;

import io.github.trae.database.filter.enums.SortDirection;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a single field within an {@link Index}, pairing the field name
 * with its sort direction.
 *
 * <p>Created internally by {@link Index#on(String, SortDirection)} — not
 * intended for direct construction by consumers.</p>
 *
 * @see Index
 */
@AllArgsConstructor
@Getter
public class IndexEntry {

    /**
     * The field name to index.
     */
    private final String field;

    /**
     * The sort direction for this field within the index.
     */
    private final SortDirection direction;
}