package io.github.trae.database.entity.update;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * One entity change, as it travels between instances.
 *
 * <p>Carries column names rather than properties: a property holds a getter, a
 * setter and a jOOQ type, none of which survive serialisation. The receiving
 * instance resolves each name back through the registry.</p>
 *
 * @see io.github.trae.database.entity.EntityHolder#listenForEntityUpdates()
 */
@AllArgsConstructor
@Getter
public class EntityUpdateDto {

    /**
     * Which instance published this, and which entity changed. The publisher
     * skips its own messages rather than evicting what it just cached.
     */
    private final UUID instanceId, id;

    /**
     * The columns the change touched, named as they are registered.
     */
    private final List<String> columnList;
}