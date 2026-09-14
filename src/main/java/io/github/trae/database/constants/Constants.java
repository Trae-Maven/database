package io.github.trae.database.constants;

import com.google.gson.Gson;
import lombok.experimental.UtilityClass;

/**
 * Shared constants for the database library.
 */
@UtilityClass
public class Constants {

    /**
     * The Gson instance used for every serialisation the library performs:
     * entities into Redis, JSON-backed property values into {@code jsonb}
     * columns, and update messages onto the pub/sub channel.
     *
     * <p>Configured with defaults, so a type Gson does not handle natively needs
     * a {@link io.github.trae.database.entity.property.converter.ValueConverter}
     * rather than a registered adapter.</p>
     */
    public static final Gson GSON = new Gson();
}