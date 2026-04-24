package io.github.trae.database.domain.data;

import io.github.trae.database.domain.data.interfaces.IDomainData;
import io.github.trae.database.domain.models.DomainProperty;
import io.github.trae.utilities.UtilJava;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

/**
 * Intermediate data carrier between raw database results and typed domain objects.
 *
 * <p>When the database driver returns a {@link LinkedHashMap}, the repository
 * wraps it in a {@code DomainData} instance. Domain constructors then use
 * {@link #get(Class, Enum)} to pull typed values by property enum constant,
 * avoiding direct map access in business logic.</p>
 *
 * <p>The flow is: <strong>Database → LinkedHashMap → DomainData → Domain</strong></p>
 *
 * @param <Property> the property enum type for the target domain
 * @see io.github.trae.database.domain.models.Domain
 */
@AllArgsConstructor
public class DomainData<Property extends Enum<?> & DomainProperty> implements IDomainData<Property> {

    /**
     * The unique identifier extracted from the database result ({@code _id}).
     */
    @Getter
    private final UUID identifier;

    /**
     * The raw key-value data from the database, keyed by property name.
     */
    private final LinkedHashMap<String, Object> map;

    /**
     * Retrieves a typed value for the given property, falling back to
     * the provided default if the key is absent or {@code null}.
     *
     * @param clazz        the expected return type class
     * @param property     the property to look up
     * @param defaultValue the fallback value if absent
     * @param <ReturnType> the expected return type
     * @return the cast value, or {@code defaultValue} if not present
     */
    @Override
    public <ReturnType> ReturnType get(final Class<ReturnType> clazz, final Property property, final ReturnType defaultValue) {
        return UtilJava.cast(clazz, Optional.ofNullable(this.map.get(property.name())).orElse(defaultValue));
    }

    /**
     * Checks whether the underlying map contains a value for the given property.
     *
     * @param property the property to check
     * @return {@code true} if the key exists in the map
     */
    @Override
    public boolean contains(final Property property) {
        return this.map.containsKey(property.name());
    }
}