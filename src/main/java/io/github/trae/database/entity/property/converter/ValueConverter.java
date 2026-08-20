package io.github.trae.database.entity.property.converter;

import org.jooq.Converter;
import org.jooq.DataType;

/**
 * Describes how a Java value is stored in, and read back from, a column of a
 * different type.
 *
 * <p>Implementations declare the storage type and its SQL data type, then supply
 * the two directions of the conversion. {@link #toDataType()} wraps all of that
 * into a converted jOOQ {@link DataType}, which is what an
 * {@link io.github.trae.database.entity.property.EntityProperty} actually holds
 * — so serialisation happens automatically on save and update, and
 * deserialisation on every read, without the repository or batch queue knowing
 * a converter exists.</p>
 *
 * <p>This interface exists so that property holders never reference jOOQ's
 * {@link Converter} directly, and so the storage type is the converter's choice
 * rather than a fixed one.</p>
 *
 * @param <Value>  the type the value is held as in Java
 * @param <Stored> the type the value is stored as in the database
 * @see io.github.trae.database.entity.property.converter.types.EnumValueConverter
 * @see io.github.trae.database.entity.property.converter.types.JsonValueConverter
 */
public interface ValueConverter<Value, Stored> {

    /**
     * Returns the SQL data type of the column the value is stored in.
     *
     * @return the storage type's jOOQ data type
     */
    DataType<Stored> getDataType();

    /**
     * Returns the Java class of the value as held on the entity.
     *
     * @return the value type
     */
    Class<Value> getValueType();

    /**
     * Returns the Java class the value is stored as.
     *
     * @return the storage type
     */
    Class<Stored> getStoredType();

    /**
     * Converts a value into its stored form on the way to the database.
     *
     * <p>Never called with {@code null} — nulls pass straight through.</p>
     *
     * @param value the value to convert
     * @return the stored representation
     */
    Stored serialize(final Value value);

    /**
     * Converts a stored value back into its Java form on the way out of the
     * database.
     *
     * <p>Never called with {@code null} — nulls pass straight through.</p>
     *
     * @param stored the stored representation
     * @return the reconstructed value
     */
    Value deserialize(final Stored stored);

    /**
     * Wraps this converter into a jOOQ data type usable as a column type.
     *
     * @return the storage data type with this conversion attached
     */
    default DataType<Value> toDataType() {
        return this.getDataType().asConvertedDataType(Converter.ofNullable(this.getStoredType(), this.getValueType(), this::deserialize, this::serialize));
    }
}