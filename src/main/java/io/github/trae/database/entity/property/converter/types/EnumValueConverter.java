package io.github.trae.database.entity.property.converter.types;

import io.github.trae.database.entity.property.converter.ValueConverter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jooq.DataType;
import org.jooq.impl.SQLDataType;

/**
 * Stores an enum constant as its {@link Enum#name()} in a {@code varchar}
 * column.
 *
 * <p>Storing the name rather than the ordinal means constants can be reordered
 * or inserted without rewriting existing rows. Renaming or removing a constant
 * still breaks reads of rows holding the old name — {@link #deserialize} throws
 * {@link IllegalArgumentException} in that case.</p>
 *
 * <pre>{@code
 * EntityProperty.registerWithConverter(Account.class, "role", Account::getRole, Account::setRole, new EnumValueConverter<>(AccountRole.class));
 * }</pre>
 *
 * @param <Value> the enum type being stored
 */
@AllArgsConstructor
@Getter
public class EnumValueConverter<Value extends Enum<Value>> implements ValueConverter<Value, String> {

    /**
     * The enum class, used to resolve names back into constants.
     */
    private final Class<Value> valueType;

    /**
     * {@inheritDoc}
     *
     * @return {@link SQLDataType#VARCHAR}
     */
    @Override
    public DataType<String> getDataType() {
        return SQLDataType.VARCHAR;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code String.class}
     */
    @Override
    public Class<String> getStoredType() {
        return String.class;
    }

    /**
     * Converts a constant to its declared name.
     *
     * @param value the enum constant
     * @return the constant's name
     */
    @Override
    public String serialize(final Value value) {
        return value.name();
    }

    /**
     * Resolves a stored name back into its enum constant.
     *
     * @param stored the constant's name as stored
     * @return the matching constant
     * @throws IllegalArgumentException if no constant with that name exists
     */
    @Override
    public Value deserialize(final String stored) {
        return Enum.valueOf(this.valueType, stored);
    }
}