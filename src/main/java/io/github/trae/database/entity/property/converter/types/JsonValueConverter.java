package io.github.trae.database.entity.property.converter.types;

import com.google.gson.reflect.TypeToken;
import io.github.trae.database.constants.Constants;
import io.github.trae.database.entity.property.converter.ValueConverter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jooq.DataType;
import org.jooq.JSONB;
import org.jooq.impl.SQLDataType;

import java.lang.reflect.Type;
import java.util.List;

/**
 * Stores an arbitrary object as JSON in a {@code jsonb} column, using Gson for
 * both directions.
 *
 * <p>Suited to values written and read whole — a nested record, a token, a list
 * of identifiers. A value that needs filtering or indexing on its inner fields
 * should be flattened into real columns instead, since querying inside a JSONB
 * document is comparatively expensive.</p>
 *
 * <p>Generic values need their full type, not just the raw class, or Gson has no
 * element type to deserialise into. {@link #ofList(Class)} builds that type for
 * the common list case; anything more involved can pass a {@link Type} directly
 * through the two-argument constructor.</p>
 *
 * <pre>{@code
 * EntityProperty.registerWithConverter(Account.class, "refreshToken", Account::getRefreshToken, Account::setRefreshToken, new JsonValueConverter<>(RefreshToken.class));
 * EntityProperty.registerWithConverter(Account.class, "permissionList", Account::getPermissionList, Account::setPermissionList, JsonValueConverter.ofList(String.class));
 * }</pre>
 *
 * @param <Value> the type being stored as JSON
 */
@AllArgsConstructor
@Getter
public class JsonValueConverter<Value> implements ValueConverter<Value, JSONB> {

    /**
     * The raw class of the value, as required by {@link ValueConverter}.
     */
    private final Class<Value> valueType;

    /**
     * The full generic type handed to Gson. Equal to {@link #valueType} for
     * non-generic values.
     */
    private final Type genericType;

    /**
     * Creates a converter for a non-generic type.
     *
     * @param valueType the class being stored
     */
    public JsonValueConverter(final Class<Value> valueType) {
        this(valueType, valueType);
    }

    /**
     * Creates a converter for a {@link List} of the given element type,
     * preserving the element type through serialisation.
     *
     * @param <Value>   the element type
     * @param valueType the element class
     * @return a converter storing a list of that element type as JSON
     */
    @SuppressWarnings("unchecked")
    public static <Value> JsonValueConverter<List<Value>> ofList(final Class<Value> valueType) {
        return new JsonValueConverter<>((Class<List<Value>>) (Class<?>) List.class, TypeToken.getParameterized(List.class, valueType).getType());
    }

    /**
     * {@inheritDoc}
     *
     * @return {@link SQLDataType#JSONB}
     */
    @Override
    public DataType<JSONB> getDataType() {
        return SQLDataType.JSONB;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code JSONB.class}
     */
    @Override
    public Class<JSONB> getStoredType() {
        return JSONB.class;
    }

    /**
     * Encodes the value as a JSONB document.
     *
     * @param value the value to encode
     * @return the value as JSONB
     */
    @Override
    public JSONB serialize(final Value value) {
        return JSONB.valueOf(Constants.GSON.toJson(value, this.genericType));
    }

    /**
     * Decodes a JSONB document back into the value type.
     *
     * @param stored the stored document
     * @return the reconstructed value
     */
    @Override
    public Value deserialize(final JSONB stored) {
        return Constants.GSON.fromJson(stored.data(), this.genericType);
    }
}