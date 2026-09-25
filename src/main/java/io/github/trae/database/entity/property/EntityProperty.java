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
 * Binds a property of an entity to the getter and setter that read and write
 * it, along with the jOOQ {@link DataType} describing how it is represented
 * when persisted.
 *
 * <p>Properties are declared as {@code public static final} constants on a holder
 * class per entity and created through {@link #register} or
 * {@link #registerWithConverter}, both of which add the property to a static
 * registry keyed by entity type. Persistent properties are used by
 * {@link io.github.trae.database.repository.EntityRepository} for schema
 * creation, saves, reads and updates. Non-persistent properties remain
 * registered and may still participate in caching and entity update
 * propagation without being stored in the database.</p>
 *
 * <p>Because registration happens in a static initialiser, the holder class must
 * be loaded before the repository performs any schema or read work; referencing
 * any one of its constants is enough to trigger that. The same applies to
 * {@link #getEntityPropertyByColumn}, which can only resolve a property the
 * holder class has already registered.</p>
 *
 * <pre>{@code
 * public class AccountProperty {
 *
 *     public static final EntityProperty<Account, String> EMAIL = EntityProperty.register(Account.class, "email", Account::getEmail, Account::setEmail, SQLDataType.VARCHAR, true);
 *     public static final EntityProperty<Account, Boolean> ONLINE = EntityProperty.register(Account.class, "online", Account::isOnline, Account::setOnline, SQLDataType.BOOLEAN, false);
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
     * Whether this property is persisted in the database.
     *
     * <p>A persistent property participates in database schema creation, reads,
     * inserts and updates. A non-persistent property remains registered and may
     * still participate in caching and entity update propagation, but is excluded
     * from database operations.</p>
     */
    private final boolean persistent;

    /**
     * Creates a property and registers it against the given entity type.
     *
     * @param <Entity>   the entity type
     * @param <Value>    the property's value type
     * @param type       the entity class the property belongs to
     * @param column     the column name in the entity's table
     * @param getter     reads the value from an entity
     * @param setter     writes the value onto an entity
     * @param dataType   the jOOQ data type for the column
     * @param persistent whether the property is persisted in the database
     * @return the newly created property, to be held as a constant
     */
    public static <Entity extends io.github.trae.database.entity.Entity, Value> EntityProperty<Entity, Value> register(final Class<Entity> type, final String column, final Function<Entity, Value> getter, final BiConsumer<Entity, Value> setter, final DataType<Value> dataType, final boolean persistent) {
        final EntityProperty<Entity, Value> entityProperty = new EntityProperty<>(column, getter, setter, dataType, persistent);

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
     * @param persistent     whether the property is persisted in the database
     * @return the newly created property, to be held as a constant
     */
    public static <Entity extends io.github.trae.database.entity.Entity, Value, Stored> EntityProperty<Entity, Value> registerWithConverter(final Class<Entity> type, final String column, final Function<Entity, Value> getter, final BiConsumer<Entity, Value> setter, final ValueConverter<Value, Stored> valueConverter, final boolean persistent) {
        return register(type, column, getter, setter, valueConverter.toDataType(), persistent);
    }

    /**
     * Returns every property registered for the specified entity type and its
     * entity superclasses.
     *
     * <p>Properties registered against an abstract entity type are inherited by
     * concrete entity implementations.</p>
     *
     * @param <Entity> the entity type
     * @param type     the entity class
     * @return the registered properties, including inherited properties
     */
    @SuppressWarnings("unchecked")
    public static <Entity extends io.github.trae.database.entity.Entity> List<EntityProperty<? super Entity, ?>> getEntityPropertyList(final Class<Entity> type) {
        final List<EntityProperty<? super Entity, ?>> entityPropertyList = new ArrayList<>();

        Class<?> currentType = type;

        while (currentType != null && currentType != io.github.trae.database.entity.Entity.class) {
            REGISTRY_MAP.getOrDefault(currentType, List.of()).forEach(entityProperty ->
                    entityPropertyList.add((EntityProperty<? super Entity, ?>) entityProperty)
            );

            currentType = currentType.getSuperclass();
        }

        return List.copyOf(entityPropertyList);
    }

    /**
     * Finds a registered property by its column name.
     *
     * <p>Resolves a property that arrived as a bare name, such as one carried
     * across instances on the entity update channel, back to the registered
     * constant.</p>
     *
     * @param <Entity> the entity type
     * @param type     the entity class the property belongs to
     * @param column   the column name to match
     * @return the matching property, or {@code null} if the entity has none by
     * that name
     */
    public static <Entity extends io.github.trae.database.entity.Entity> EntityProperty<? super Entity, ?> getEntityPropertyByColumn(final Class<Entity> type, final String column) {
        for (final EntityProperty<? super Entity, ?> entityProperty : getEntityPropertyList(type)) {
            if (entityProperty.getColumn().equals(column)) {
                return entityProperty;
            }
        }

        return null;
    }

    /**
     * Initializes the specified property holder class.
     *
     * <p>Forces static initialization so its entity properties are registered
     * before repository schema operations are performed.</p>
     *
     * @param propertyType the property holder class to initialize
     * @throws IllegalStateException if the property holder class cannot be initialized
     */
    public static void loadPropertyType(final Class<?> propertyType) {
        try {
            Class.forName(propertyType.getName(), true, propertyType.getClassLoader());
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException("Failed to initialize property type '%s'.".formatted(propertyType.getName()), e);
        }
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