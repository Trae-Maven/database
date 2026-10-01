package io.github.trae.database.storage.types;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.trae.database.constants.Constants;
import io.github.trae.database.driver.RedisDriver;
import io.github.trae.database.entity.TenantEntity;
import io.github.trae.database.repository.EntityRepository;
import io.github.trae.database.storage.RedisStorage;
import io.github.trae.utilities.UtilJava;

import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * The Redis tier holding entities under their own identifier.
 *
 * <p>The shared copy every instance reads back from, and the source of truth
 * between them: a writer refreshes this, the others drop their local copies and
 * find the new state here on their next lookup. Namespaced from the repository's
 * database name and the entity's simple name, so every instance on the same
 * database agrees on the key without being told it, and two databases holding
 * the same entity type never share entries.</p>
 *
 * <p>Entities are stored as JSON through {@link Constants#GSON}, so anything held
 * on one needs to survive a default Gson round trip. The tenant of a
 * {@link TenantEntity} is the exception: it is written alongside the entity's
 * own fields and restored on read, so it survives regardless of how the entity
 * declares it. An entry missing its tenant is treated as corrupt, evicted and
 * refetched.</p>
 *
 * <p>When the repository is tenant-scoped, every read hides entities belonging
 * to another tenant, including {@link #keys()}, {@link #values()} and
 * {@link #size()}.</p>
 *
 * <p>A subclass supplies only the TTL.</p>
 *
 * @param <Entity> the entity type held
 */
public abstract class IdRedisStorage<Entity extends io.github.trae.database.entity.Entity> extends RedisStorage<Entity, Entity> {

    /**
     * The JSON property a tenant entity's tenant is stored under, named so it
     * cannot collide with a field on the entity.
     */
    private static final String TENANT_PROPERTY = "__tenant_id";

    /**
     * The repository serving the entity, supplying its class, its database and
     * its tenant scoping.
     */
    private final EntityRepository<Entity> entityRepository;

    /**
     * The visibility rule, built once since the driver's tenant is fixed, or
     * {@code null} when the repository is not tenant-scoped.
     */
    private final BiPredicate<String, Entity> visibility;

    /**
     * @param redisDriver      the connection this tier reads and writes through
     * @param entityRepository the repository serving the entity, constructed
     *                         after its driver has connected
     */
    public IdRedisStorage(final RedisDriver redisDriver, final EntityRepository<Entity> entityRepository) {
        super(redisDriver, "%s:%s:id".formatted(entityRepository.getDatabaseDriver().getDatabaseName(), entityRepository.getEntityType().getSimpleName().toLowerCase(Locale.ROOT)));

        this.entityRepository = entityRepository;
        this.visibility = entityRepository.isTenantScoped() ? (key, entity) -> TenantEntity.isVisible(entity, entityRepository.getDatabaseDriver().getTenantId()) : null;
    }

    /**
     * Encodes the entity as JSON, adding its tenant for a {@link TenantEntity}.
     *
     * @param entity the entity to store
     * @return the stored form
     */
    @Override
    protected final String serialize(final Entity entity) {
        final JsonElement jsonElement = Constants.GSON.toJsonTree(entity);

        if (this.entityRepository.isTenantEntity()) {
            jsonElement.getAsJsonObject().addProperty(TENANT_PROPERTY, TenantEntity.resolveTenantId(entity));
        }

        return Constants.GSON.toJson(jsonElement);
    }

    /**
     * Rebuilds an entity from its stored JSON, restoring its tenant for a
     * {@link TenantEntity}.
     *
     * @param value the stored form
     * @return the reconstructed entity, or {@code null} for a tenant entity
     * stored without its tenant
     */
    @Override
    protected final Entity deserialize(final String value) {
        final JsonObject jsonObject = JsonParser.parseString(value).getAsJsonObject();
        final Entity entity = Constants.GSON.fromJson(jsonObject, this.entityRepository.getEntityType());

        if (entity == null || !this.entityRepository.isTenantEntity()) {
            return entity;
        }

        final JsonElement tenantElement = jsonObject.get(TENANT_PROPERTY);
        if (tenantElement == null || tenantElement.isJsonNull()) {
            return null;
        }

        UtilJava.cast(TenantEntity.class, entity).setTenantId(tenantElement.getAsString());

        return entity;
    }

    /**
     * Stores the entity under its identifier.
     *
     * @param entity the entity to cache
     */
    @Override
    public final void index(final Entity entity) {
        this.put(entity.getId().toString(), entity);
    }

    /**
     * Drops the entity's shared copy.
     *
     * @param entity the entity to evict
     */
    @Override
    public final void unIndex(final Entity entity) {
        this.remove(entity.getId().toString());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hides entities belonging to another tenant when the repository is
     * tenant-scoped.</p>
     */
    @Override
    protected final BiPredicate<String, Entity> getVisibility() {
        return this.visibility;
    }
}