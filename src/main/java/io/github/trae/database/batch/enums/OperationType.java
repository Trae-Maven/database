package io.github.trae.database.batch.enums;

/**
 * The kind of statement a queued write renders into.
 *
 * <p>Chosen by the repository method that queued the write, and decides which
 * branch of {@link io.github.trae.database.batch.data.PendingWrite#toQuery} runs
 * when the queue flushes.</p>
 */
public enum OperationType {

    /**
     * Writes every column as an upsert — an insert that falls back to updating
     * the matching row. Queued by a full save, where the entity may not exist
     * yet.
     */
    SAVE,

    /**
     * Writes only the columns named in the write's value map, matching on the
     * identifier. Queued by a partial update of an existing row.
     */
    UPDATE,

    /**
     * Removes the row matching the identifier. Column values are discarded when a
     * write merges into this type.
     */
    DELETE
}