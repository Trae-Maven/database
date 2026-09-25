package io.github.trae.database.batch.data;

import io.github.trae.database.batch.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Table;
import org.jooq.impl.DSL;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One entity's pending write, held in the queue until the next flush.
 *
 * <p>Immutable. A second write to the same entity does not replace this one — it
 * is folded in through {@link #merge(PendingWrite)}, producing a new instance
 * carrying both sets of columns. That is what turns a burst of edits to one
 * entity into a single statement.</p>
 *
 * <p>The sequence number is stamped when the entity first enters the queue and
 * survives every merge, so the flush can order writes across entities by the
 * order they arrived — a row cannot be deleted before the insert that created
 * it.</p>
 *
 * <p>Values are captured at queue time rather than read at flush time, so a field
 * mutated without a matching update call does not leak into the row.</p>
 *
 * @see io.github.trae.database.batch.BatchQueue
 */
@AllArgsConstructor
@Getter
public class PendingWrite {

    /**
     * The table being written to, and the queue key identifying this entity
     * within it.
     */
    private final String table, key;

    /**
     * Position in the arrival order, assigned once and preserved across merges.
     */
    private final long sequence;

    /**
     * The identifier column, used as the match condition and conflict target.
     */
    private final Field<UUID> identifierField;

    /**
     * The entity's identifier.
     */
    private final UUID identifierValue;

    /**
     * Columns to write, in insertion order. Empty for a delete.
     */
    private final Map<Field<?>, Object> valueMap;

    /**
     * The kind of statement this write renders into.
     */
    private final OperationType operationType;

    /**
     * Folds a newer write for the same entity into this one.
     *
     * <p>Column values from the newer write override existing values where they
     * overlap. A pending save remains a save when followed by an update so an
     * entity that has not yet been inserted is still created. A delete discards
     * all accumulated column values.</p>
     *
     * @param pendingWrite the newly queued write
     * @return a write representing both
     */
    public PendingWrite merge(final PendingWrite pendingWrite) {
        if (pendingWrite.getOperationType() == OperationType.DELETE) {
            return new PendingWrite(this.table, this.key, this.sequence, this.identifierField, this.identifierValue, Collections.emptyMap(), OperationType.DELETE);
        }

        final Map<Field<?>, Object> mergedValueMap = new LinkedHashMap<>(this.valueMap);
        mergedValueMap.putAll(pendingWrite.getValueMap());

        final OperationType operationType = this.operationType == OperationType.SAVE && pendingWrite.getOperationType() == OperationType.UPDATE ? OperationType.SAVE : pendingWrite.getOperationType();

        return new PendingWrite(this.table, this.key, this.sequence, this.identifierField, this.identifierValue, mergedValueMap, operationType);
    }

    /**
     * Renders this write into a jOOQ query.
     *
     * <p>Any converter carried by a column's field applies to the bind values
     * here, so a value is serialised on its way into the statement.</p>
     *
     * @param dslContext the context to build against, transactional at flush time
     * @return the statement to execute
     */
    public Query toQuery(final DSLContext dslContext) {
        final Table<?> table = DSL.table(DSL.name(this.table));

        return switch (this.operationType) {
            case SAVE -> dslContext.insertInto(table).set(this.identifierField, this.identifierValue).set(this.valueMap).onConflict(this.identifierField).doUpdate().set(this.valueMap);
            case UPDATE -> dslContext.update(table).set(this.valueMap).where(this.identifierField.eq(this.identifierValue));
            case DELETE -> dslContext.deleteFrom(table).where(this.identifierField.eq(this.identifierValue));
        };
    }
}