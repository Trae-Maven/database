package io.github.trae.database.entity.property;

import io.github.trae.database.entity.property.converter.ValueConverter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.impl.DSL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Binds one column of an entity's table to the getter and setter that read and
 * write it, along with the jOOQ {@link DataType} describing how it is stored.
 *
 * <p>Properties are declared as {@code public static final} constants on a holder
 * class per entity and created through {@link #register} or
 * {@link #registerWithConverter}, both of which add the property to a static
 * registry keyed by entity type. That registry is what
 * {@link io.github.trae.database.repository.EntityRepository} reads to know an
 * entity's full column set — for schema creation, for full saves, and for
 * rebuilding an entity from a result row.</p>
 *
 * <p>Because registration happens in a static initialiser, the holder class must
 * be loaded before the repository performs any schema or read work; referencing
 * any one of its constants is enough to trigger that.</p>
 *
 * <pre>{@code
 * public class AccountProperty {
 *
 *     public static final EntityProperty<Account, String> EMAIL = EntityProperty.register(Account.class, "email", Account::getEmail, Account::setEmail, SQLDataType.VARCHAR);
 * }
 * }</pre>
 *
 * @param <Entity> the entity type this property belongs to
 * @param <Value>  the Java type of the property's value
 * @see ValueConverter
 * @see io.github.trae.database.repository.EntityRepository
 */
@AllArgsConstructor
@Getter
public final class EntityProperty<Entity extends io.github.trae.database.entity.Entity, Value> {

    /**
     * Every registered property, grouped by the entity type it was registered
     * against. Populated by {@link #register} as holder classes initialise.
     */
    private static final Map<Class<?>, List<EntityProperty<?, ?>>> REGISTRY_MAP = new ConcurrentHashMap<>();

    /**
     * The column name in the entity's table.
     */
    private final String column;

    /**
     * Reads this property's value from an entity instance.
     */
    private final Function<Entity, Value> getter;

    /**
     * Writes this property's value onto an entity instance.
     */
    private final BiConsumer<Entity, Value> setter;

    /**
     * The jOOQ data type for the column, including any attached converter.
     */
    private final DataType<Value> dataType;

    /**
     * Creates a property and registers it against the given entity type.
     *
     * @param <Entity> the entity type
     * @param <Value>  the property's value type
     * @param type     the entity class the property belongs to
     * @param column   the column name in the entity's table
     * @param getter   reads the value from an entity
     * @param setter   writes the value onto an entity
     * @param dataType the jOOQ data type for the column
     * @return the newly created property, to be held as a constant
     */
    public static <Entity extends io.github.trae.database.entity.Entity, Value> EntityProperty<Entity, Value> register(final Class<Entity> type, final String column, final Function<Entity, Value> getter, final BiConsumer<Entity, Value> setter, final DataType<Value> dataType) {
        final EntityProperty<Entity, Value> entityProperty = new EntityProperty<>(column, getter, setter, dataType);

        REGISTRY_MAP.computeIfAbsent(type, ignored -> new ArrayList<>()).add(entityProperty);

        return entityProperty;
    }

    /**
     * Creates a property whose value is stored in a different type than it is
     * held in, converting in both directions through the given converter.
     *
     * <p>The column's SQL type comes from the converter, so a JSON converter
     * produces a {@code jsonb} column and an enum converter a {@code varchar}
     * one. Serialisation on write and deserialisation on read are handled by
     * jOOQ; nothing downstream is aware a conversion is taking place.</p>
     *
     * @param <Entity>       the entity type
     * @param <Value>        the property's value type as held in Java
     * @param <Stored>       the type the value is stored as
     * @param type           the entity class the property belongs to
     * @param column         the column name in the entity's table
     * @param getter         reads the value from an entity
     * @param setter         writes the value onto an entity
     * @param valueConverter converts between the value and stored types
     * @return the newly created property, to be held as a constant
     */
    public static <Entity extends io.github.trae.database.entity.Entity, Value, Stored> EntityProperty<Entity, Value> registerWithConverter(final Class<Entity> type, final String column, final Function<Entity, Value> getter, final BiConsumer<Entity, Value> setter, final ValueConverter<Value, Stored> valueConverter) {
        return register(type, column, getter, setter, valueConverter.toDataType());
    }

    /**
     * Returns every property registered against the given entity type, in
     * declaration order.
     *
     * <p>Returns an empty list if the entity's property holder class has not been
     * initialised yet, which would leave a table with no columns beyond its
     * identifier.</p>
     *
     * @param <Entity> the entity type
     * @param type     the entity class to look up
     * @return the registered properties, empty if none
     */
    @SuppressWarnings("unchecked")
    public static <Entity extends io.github.trae.database.entity.Entity> List<EntityProperty<Entity, ?>> getEntityPropertyList(final Class<Entity> type) {
        return (List<EntityProperty<Entity, ?>>) (List<?>) REGISTRY_MAP.getOrDefault(type, List.of());
    }

    /**
     * Builds the jOOQ field for this property's column.
     *
     * <p>Carries the property's data type, so any attached converter applies to
     * both bind values and fetched results.</p>
     *
     * @return a typed field naming this property's column
     */
    public Field<Value> getField() {
        return DSL.field(DSL.name(this.column), this.dataType);
    }

    /**
     * Reads this property's current value from the given entity.
     *
     * @param entity the entity to read from
     * @return the property's value, possibly {@code null}
     */
    public Value getValue(final Entity entity) {
        return this.getter.apply(entity);
    }
}