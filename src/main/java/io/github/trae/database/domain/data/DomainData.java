package io.github.trae.database.domain.data;

import io.github.trae.database.domain.data.interfaces.IDomainData;
import io.github.trae.database.domain.models.SharedDomainProperty;
import io.github.trae.database.domain.models.SubDomainProperty;
import io.github.trae.utilities.UtilJava;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.*;
import java.util.function.Function;

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
public class DomainData<Property extends Enum<?> & SharedDomainProperty> implements IDomainData<Property> {

    /**
     * The unique identifier extracted from the database result ({@code _id}).
     */
    @Getter
    private final UUID identifier;

    /**
     * The raw key-value data from the database, keyed by property name.
     */
    private final LinkedHashMap<String, Object> map;

    public DomainData(final LinkedHashMap<String, Object> map) {
        this(UtilJava.cast(UUID.class, map.remove("_id")), map);
    }

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
     * Retrieves a typed list for the given property.
     *
     * <p>Reads the raw value as a {@link Collection} and filters elements
     * by the given class, silently skipping any that do not match.</p>
     *
     * @param clazz    the expected element type class
     * @param property the property to look up
     * @param <Type>   the expected element type
     * @return a mutable list of matching elements, empty if absent or no matches
     */
    @Override
    public <Type> List<Type> getList(final Class<Type> clazz, final Property property) {
        return UtilJava.createCollection(new ArrayList<>(), list -> {
            if (this.get(Object.class, property) instanceof final Collection<?> collection) {
                for (final Object object : collection) {
                    if (!(clazz.isInstance(object))) {
                        continue;
                    }

                    list.add(UtilJava.cast(clazz, object));
                }
            }
        });
    }

    /**
     * Retrieves a typed map for the given property.
     *
     * <p>Reads the raw value as a {@link Map} and filters entries where both
     * the key and value match the given classes, silently skipping mismatches.</p>
     *
     * @param keyClass   the expected key type class
     * @param valueClass the expected value type class
     * @param property   the property to look up
     * @param <Key>      the expected key type
     * @param <Value>    the expected value type
     * @return a mutable {@link LinkedHashMap} of matching entries, empty if absent or no matches
     */
    @Override
    public <Key, Value> LinkedHashMap<Key, Value> getMap(final Class<Key> keyClass, final Class<Value> valueClass, final Property property) {
        return UtilJava.createMap(new LinkedHashMap<>(), linkedHashMap -> {
            if (this.get(Object.class, property) instanceof final Map<?, ?> rawMap) {
                for (final Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    final Object key = entry.getKey();
                    if (!(keyClass.isInstance(key))) {
                        continue;
                    }

                    final Object value = entry.getValue();
                    if (!(valueClass.isInstance(value))) {
                        continue;
                    }

                    linkedHashMap.put(UtilJava.cast(keyClass, key), UtilJava.cast(valueClass, value));
                }
            }
        });
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

    /**
     * Retrieves a map of {@link SubDomain} entries, constructing each instance
     * via the provided constructor function.
     *
     * <p>Reads the raw value as a {@code Map<String, LinkedHashMap>} via
     * {@link #getMap}, then converts each entry by parsing the string key
     * as a {@link UUID} identifier, wrapping the nested map in a
     * {@link DomainData}, and passing it to the constructor function.</p>
     *
     * @param property      the property to look up
     * @param constructor   the function to construct the sub-domain from {@link DomainData}
     * @param <SubProperty> the property enum type of the sub-domain
     * @param <SubDomain>   the sub-domain type to construct
     * @return a mutable map of UUID to constructed sub-domain instances, empty if absent
     */
    @SuppressWarnings("unchecked")
    @Override
    public <SubProperty extends Enum<?> & SubDomainProperty, SubDomain> LinkedHashMap<UUID, SubDomain> getSubDomainMap(final Property property, final Function<DomainData<SubProperty>, SubDomain> constructor) {
        return UtilJava.createMap(new LinkedHashMap<>(), map -> {
            this.getMap(String.class, LinkedHashMap.class, property).forEach((key, value) -> {
                final UUID identifier = UUID.fromString(key);

                map.put(identifier, constructor.apply(new DomainData<>(identifier, (LinkedHashMap<String, Object>) value)));
            });
        });
    }
}