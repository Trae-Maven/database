package io.github.trae.database.repository;

import io.github.trae.database.DatabaseApi;
import io.github.trae.database.batch.enums.OperationType;
import io.github.trae.database.driver.DatabaseDriver;
import io.github.trae.database.entity.property.EntityProperty;
import io.github.trae.database.repository.enums.IndexType;
import lombok.Getter;
import lombok.Setter;
import org.jooq.Condition;
import org.jooq.CreateTableElementListStep;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Database access for one entity type — schema management, reads, and writes.
 *
 * <p>Subclassed once per entity, passing the entity class and table name up. The
 * subclass typically adds domain-specific finders on top of the generic ones and
 * overrides {@link #getIndexes()} to declare its indexes.</p>
 *
 * <p>Reads run immediately against the connection pool. Writes never do — they go
 * to the driver's {@link io.github.trae.database.batch.BatchQueue}, where writes
 * to the same entity coalesce and commit together on the next flush. A read
 * issued while a write for the same entity is still queued returns the row as it
 * stands in the database, not as it will be.</p>
 *
 * <p>Schema work is driven by the properties registered against the entity type,
 * each of which carries its own column type. The repository registers itself
 * with {@link DatabaseApi} on construction, and the driver runs
 * {@link #createTable()}, {@link #migrateSchema()} and {@link #createIndexes()}
 * across every registered repository during {@link DatabaseDriver#connect()}.</p>
 *
 * @param <Entity> the entity type this repository serves
 * @see EntityProperty
 * @see io.github.trae.database.batch.BatchQueue
 */
@Getter
public class EntityRepository<Entity extends io.github.trae.database.entity.Entity> {

    /**
     * Name of the identifier column, shared by every entity table.
     */
    private static final String IDENTIFIER_COLUMN = "id";

    /**
     * The identifier field, used as primary key, match condition and conflict
     * target throughout the library.
     */
    public static final Field<UUID> IDENTIFIER_FIELD = DSL.field(DSL.name(IDENTIFIER_COLUMN), SQLDataType.UUID);

    /**
     * The driver supplying the jOOQ context and batch queue.
     */
    private final DatabaseDriver databaseDriver;

    /**
     * The entity class, used to look up its registered properties.
     */
    private final Class<Entity> entityType;

    /**
     * The table name as given by the subclass.
     */
    private final String tableName;

    /**
     * The table reference used by every query, built once from the lowercased
     * table name.
     */
    private final Table<Record> table;

    /**
     * The entity's identifier constructor, resolved once and reused for every
     * row.
     */
    private final Constructor<Entity> entityConstructor;

    /**
     * Whether this repository has finished loading whatever it needs at startup.
     *
     * <p>Set by the consumer once the repository is ready — after a warm-up read,
     * a cache prime, or whatever that entity's startup involves.
     * {@link DatabaseApi#isDatabaseLoaded()} reports true only once every
     * registered repository has been marked, giving the application one flag to
     * gate on before it starts serving.</p>
     */
    @Setter
    private boolean loaded;

    /**
     * Resolves the entity's constructor and registers with the driver.
     *
     * <p>No database work happens here — the driver performs schema setup for
     * every registered repository during {@link DatabaseDriver#connect()}, which
     * is why repositories are constructed before the driver connects.</p>
     *
     * @param databaseDriver the driver to read and write through
     * @param entityType     the entity class
     * @param tableName      the table name
     * @throws IllegalArgumentException if the entity declares no constructor
     *                                  taking a {@link UUID}
     * @see DatabaseApi#addRepository(EntityRepository)
     */
    public EntityRepository(final DatabaseDriver databaseDriver, final Class<Entity> entityType, final String tableName) {
        this.databaseDriver = databaseDriver;
        this.entityType = entityType;
        this.tableName = tableName;
        this.table = DSL.table(DSL.name(tableName.toLowerCase(Locale.ROOT)));

        try {
            this.entityConstructor = entityType.getDeclaredConstructor(UUID.class);
            this.entityConstructor.setAccessible(true);
        } catch (final NoSuchMethodException e) {
            throw new IllegalArgumentException("Class '%s' has no constructor taking a %s".formatted(entityType.getName(), UUID.class.getSimpleName()), e);
        }

        DatabaseApi.addRepository(this);
    }

    /**
     * Declares which properties get an index, and of what kind.
     *
     * <p>Empty by default. Override to return the columns this entity is actually
     * queried by — an index on a column no query filters or sorts on costs write
     * throughput for nothing.</p>
     *
     * @return the properties to index, mapped to their index type
     */
    protected Map<EntityProperty<? super Entity, ?>, IndexType> getIndexes() {
        return Collections.emptyMap();
    }

    /**
     * Creates the table if it does not exist, with a column per registered
     * property plus the identifier primary key.
     *
     * <p>Does nothing to an existing table, including one whose columns no longer
     * match — {@link #migrateSchema()} handles additions from there.</p>
     */
    public void createTable() {
        CreateTableElementListStep createTableElementListStep = this.databaseDriver.getDslContext().createTableIfNotExists(this.getTable()).column(IDENTIFIER_FIELD, SQLDataType.UUID.nullable(false));

        for (final EntityProperty<? super Entity, ?> entityProperty : EntityProperty.getEntityPropertyList(this.entityType)) {
            createTableElementListStep = createTableElementListStep.column(entityProperty.getField(), entityProperty.getDataType());
        }

        createTableElementListStep.constraint(DSL.primaryKey(DSL.name(IDENTIFIER_COLUMN))).execute();
    }

    /**
     * Adds any registered property that has no column yet.
     *
     * <p>Additive only. A column whose type has changed is left alone, and a
     * column whose property has been removed is left in place — so a type change
     * has to be applied by hand before the table holds rows.</p>
     */
    public void migrateSchema() {
        EntityProperty.getEntityPropertyList(this.entityType).forEach(entityProperty -> this.databaseDriver.getDslContext().alterTable(this.getTable()).addIfNotExists(DSL.name(entityProperty.getColumn()), entityProperty.getDataType()).execute());
    }

    /**
     * Drops the table and everything in it.
     */
    public void dropTable() {
        this.databaseDriver.getDslContext().dropTableIfExists(this.getTable()).execute();
    }

    /**
     * Creates every index declared by {@link #getIndexes()} that does not exist
     * yet.
     *
     * <p>Index names follow {@code idx_<table>_<column>}. The trigram and BRIN
     * variants are issued as raw SQL, since jOOQ's DDL builder has no direct
     * support for their operator classes.</p>
     */
    public void createIndexes() {
        this.getIndexes().forEach((entityProperty, indexType) -> {
            final String indexName = "idx_%s_%s".formatted(this.tableName, entityProperty.getColumn());
            final Field<?> field = entityProperty.getField();

            switch (indexType) {
                case BTREE -> this.databaseDriver.getDslContext().createIndexIfNotExists(DSL.name(indexName)).on(this.getTable(), field).execute();
                case GIN_TRGM -> this.databaseDriver.getDslContext().execute("CREATE INDEX IF NOT EXISTS {0} ON {1} USING GIN ({2} gin_trgm_ops)", DSL.name(indexName), this.getTable(), field);
                case BRIN -> this.databaseDriver.getDslContext().execute("CREATE INDEX IF NOT EXISTS {0} ON {1} USING BRIN ({2})", DSL.name(indexName), this.getTable(), field);
            }
        });
    }

    /**
     * Reads every row in the table.
     *
     * @return every entity, in no particular order
     */
    public List<Entity> findAll() {
        return this.databaseDriver.getDslContext().selectFrom(this.getTable()).fetch().map(this::build);
    }

    /**
     * Reads the first row matching a condition.
     *
     * @param condition the condition to match
     * @return the entity, or empty if nothing matches
     */
    public Optional<Entity> findOne(final Condition condition) {
        return this.databaseDriver.getDslContext().selectFrom(this.getTable()).where(condition).limit(1).fetchOptional().map(this::build);
    }

    /**
     * Reads the first row where a property equals a value.
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to match on
     * @param value          the value to match
     * @return the entity, or empty if nothing matches
     */
    public <Value> Optional<Entity> findOne(final EntityProperty<? super Entity, Value> entityProperty, final Value value) {
        return this.findOne(entityProperty.getField().eq(value));
    }

    /**
     * Reads the row with the given identifier.
     *
     * @param id the entity's identifier
     * @return the entity, or empty if no such row exists
     */
    public Optional<Entity> findById(final UUID id) {
        return this.findOne(IDENTIFIER_FIELD.eq(id));
    }

    /**
     * Reads the identifier of the first row where a property equals a value.
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to match on
     * @param value          the value to match
     * @return the identifier, or empty if nothing matches
     */
    public <Value> Optional<UUID> findIdByValue(final EntityProperty<? super Entity, Value> entityProperty, final Value value) {
        return this.databaseDriver.getDslContext()
                .select(IDENTIFIER_FIELD)
                .from(this.getTable())
                .where(entityProperty.getField().eq(value))
                .limit(1)
                .fetchOptional(IDENTIFIER_FIELD);
    }

    /**
     * Reads every row matching a condition.
     *
     * @param condition the condition to match
     * @return the matching entities, empty if none
     */
    public List<Entity> findMany(final Condition condition) {
        return this.databaseDriver.getDslContext().selectFrom(this.getTable()).where(condition).fetch().map(this::build);
    }

    /**
     * Reads every row where a property equals a value.
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to match on
     * @param value          the value to match
     * @return the matching entities, empty if none
     */
    public <Value> List<Entity> findMany(final EntityProperty<? super Entity, Value> entityProperty, final Value value) {
        return this.findMany(entityProperty.getField().eq(value));
    }

    /**
     * Reads several rows by identifier in one query.
     *
     * @param idList the identifiers to resolve
     * @return the entities found, in no particular order; identifiers with no row
     * are simply absent
     */
    public List<Entity> findManyById(final List<UUID> idList) {
        return idList.isEmpty() ? Collections.emptyList() : this.findMany(IDENTIFIER_FIELD.in(idList));
    }

    /**
     * Reads one page of rows, ordered and offset.
     *
     * @param condition the condition to match, or {@code null} for every row
     * @param sortField the ordering to apply
     * @param offset    how many rows to skip
     * @param limit     how many rows to return
     * @return the page's entities
     */
    public List<Entity> findPage(final Condition condition, final SortField<?> sortField, final int offset, final int limit) {
        return this.databaseDriver.getDslContext().selectFrom(this.getTable()).where(condition == null ? DSL.noCondition() : condition).orderBy(sortField).offset(offset).limit(limit).fetch().map(this::build);
    }

    /**
     * Reads a single column of a single row, without building the entity.
     *
     * <p>Cheaper than a full read when only one value is wanted.</p>
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to read
     * @param id             the entity's identifier
     * @return the value, or empty if the row is absent or the value is null
     */
    public <Value> Optional<Value> findValue(final EntityProperty<? super Entity, Value> entityProperty, final UUID id) {
        return this.databaseDriver.getDslContext().select(entityProperty.getField()).from(this.getTable()).where(IDENTIFIER_FIELD.eq(id)).fetchOptional(entityProperty.getField());
    }

    /**
     * Returns whether any row matches a condition, without fetching it.
     *
     * @param condition the condition to match
     * @return {@code true} if at least one row matches
     */
    public boolean exists(final Condition condition) {
        return this.databaseDriver.getDslContext().fetchExists(DSL.selectOne().from(this.getTable()).where(condition));
    }

    /**
     * Returns whether any row has the given value for a property.
     *
     * <p>The usual uniqueness check before an insert or a rename.</p>
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to match on
     * @param value          the value to match
     * @return {@code true} if at least one row matches
     */
    public <Value> boolean exists(final EntityProperty<? super Entity, Value> entityProperty, final Value value) {
        return this.exists(entityProperty.getField().eq(value));
    }

    /**
     * Returns whether a row with the given identifier exists.
     *
     * @param id the identifier to check
     * @return {@code true} if the row exists
     */
    public boolean exists(final UUID id) {
        return this.exists(IDENTIFIER_FIELD.eq(id));
    }

    /**
     * Counts every row in the table.
     *
     * @return the row count
     */
    public long count() {
        return this.databaseDriver.getDslContext().fetchCount(this.getTable());
    }

    /**
     * Counts the rows matching a condition.
     *
     * @param condition the condition to match
     * @return the matching row count
     */
    public long count(final Condition condition) {
        return this.databaseDriver.getDslContext().fetchCount(this.getTable(), condition);
    }

    /**
     * Queues a write of every column as an upsert.
     *
     * <p>For creating an entity, or for rewriting one wholesale. A partial change
     * to an existing entity belongs in {@code update(Entity, EntityProperty)}
     * instead, which writes only what changed.</p>
     *
     * @param entity the entity to persist
     */
    public void save(final Entity entity) {
        this.queue(entity, EntityProperty.getEntityPropertyList(this.entityType), OperationType.SAVE);
    }

    /**
     * Queues a write of the named columns only.
     *
     * <p>Values are read from the entity as this runs, so setters must have been
     * applied before calling.</p>
     *
     * @param entity             the entity to update
     * @param entityPropertyList the properties whose columns should be written
     */
    public void update(final Entity entity, final List<EntityProperty<? super Entity, ?>> entityPropertyList) {
        this.queue(entity, entityPropertyList, OperationType.UPDATE);
    }

    /**
     * Queues a write of a single column.
     *
     * @param entity               the entity to update
     * @param entityEntityProperty the property whose column should be written
     */
    public void update(final Entity entity, final EntityProperty<? super Entity, ?> entityEntityProperty) {
        this.update(entity, Collections.singletonList(entityEntityProperty));
    }

    /**
     * Queues removal of the entity's row.
     *
     * @param entity the entity to delete
     */
    public void delete(final Entity entity) {
        this.queue(entity, Collections.emptyList(), OperationType.DELETE);
    }

    /**
     * Rebuilds an entity from a result row.
     *
     * <p>Constructs it from the row's identifier, then applies every registered
     * property's setter. Any converter on a property's field runs here, so a
     * value arrives in its Java form rather than its stored one.</p>
     *
     * @param record the row to read
     * @return the reconstructed entity
     */
    private Entity build(final Record record) {
        final Entity entity = this.instantiate(record.get(IDENTIFIER_FIELD));

        EntityProperty.getEntityPropertyList(this.entityType).forEach(entityProperty -> this.apply(entityProperty, entity, record));

        return entity;
    }

    /**
     * Creates a bare entity holding only its identifier.
     *
     * @param id the identifier to construct with
     * @return the new entity
     * @throws IllegalStateException if the constructor could not be invoked
     */
    private Entity instantiate(final UUID id) {
        try {
            return this.entityConstructor.newInstance(id);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate '%s'.".formatted(this.entityType.getName()), e);
        }
    }

    /**
     * Applies one property's column value onto an entity.
     *
     * <p>Exists as its own method so the wildcard on a property in the list is
     * captured into a concrete type, letting the setter accept the fetched
     * value.</p>
     *
     * @param <Value>        the property's value type
     * @param entityProperty the property to apply
     * @param entity         the entity being built
     * @param record         the row being read
     */
    private <Value> void apply(final EntityProperty<? super Entity, Value> entityProperty, final Entity entity, final Record record) {
        entityProperty.getSetter().accept(entity, record.get(entityProperty.getField()));
    }

    /**
     * Renders the given properties into a value map and hands the write to the
     * batch queue.
     *
     * @param entity             the entity being written
     * @param entityPropertyList the properties whose columns to write
     * @param operationType      the kind of statement to render
     */
    private void queue(final Entity entity, final List<EntityProperty<? super Entity, ?>> entityPropertyList, final OperationType operationType) {
        this.databaseDriver.getBatchQueue().queue(this.tableName, IDENTIFIER_FIELD, entity.getId(), this.valueMap(entity, entityPropertyList), operationType);
    }

    /**
     * Reads the given properties off an entity into a field-to-value map.
     *
     * <p>Insertion-ordered, so the generated SQL keeps a stable column order and
     * writes of the same shape group together at flush time.</p>
     *
     * @param e                  the entity to read from
     * @param entityPropertyList the properties to read
     * @return the columns and their current values
     */
    private Map<Field<?>, Object> valueMap(final Entity e, final List<EntityProperty<? super Entity, ?>> entityPropertyList) {
        return entityPropertyList.stream().collect(
                LinkedHashMap::new,
                (map, entityProperty) -> map.put(entityProperty.getField(), entityProperty.getValue(e)),
                LinkedHashMap::putAll
        );
    }
}