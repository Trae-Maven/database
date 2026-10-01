package io.github.trae.database.storage;

import io.github.trae.database.driver.RedisDriver;
import io.github.trae.database.entity.TenantEntity;
import io.github.trae.database.repository.EntityRepository;

import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * Redis counterpart to {@link LocalEntityReferenceIdStorage} — maps a secondary
 * key to an entity's identifier, shared across every process pointing at the
 * same Redis.
 *
 * <p>Holds the same one-copy-per-entity discipline across the network that the
 * local tier holds in one process: whichever server writes the entity writes it
 * once under its identifier, and every key pointing at it stays a pointer. A
 * server resolving an email gets an identifier from here and the entity from the
 * identifier storage, so the two servers never disagree about what the entity
 * looks like.</p>
 *
 * <p>Identifiers are stored as their canonical string form, which is fixed-width
 * and case-stable, so a malformed value can only come from something outside
 * this class having written the key. That is treated as a corrupt entry and
 * evicted, the same as any other value that will not decode.</p>
 *
 * <p>The key is always a string here, since that is what Redis keys on.</p>
 *
 * <p>Subclasses supply only {@link #getKey(io.github.trae.database.entity.Entity)}.
 * That key is derived from the entity, so a storage keyed on something the
 * entity can change needs {@link Storage#reIndex(Object, Object)} on update: the
 * new key is derivable, the old one is not.</p>
 *
 * <p>The namespace is prefixed with the repository's database name, so two
 * databases holding the same entity type never share entries.</p>
 *
 * <p>For a {@link TenantEntity}, every key is prefixed with the entity's tenant,
 * so two tenants holding an entity under the same key never overwrite each
 * other's entry in the shared Redis. Lookups resolve the prefix through
 * {@link #getTenantKey(String, String)}, and when the repository is
 * tenant-scoped every read, including {@link #keys()}, {@link #values()} and
 * {@link #size()}, hides entries belonging to another tenant.</p>
 *
 * @param <Entity> the entity type the identifiers belong to
 */
public abstract class RedisEntityReferenceIdStorage<Entity extends io.github.trae.database.entity.Entity> extends RedisStorage<UUID, Entity> {

    /**
     * The visibility rule, built once since the driver's tenant is fixed, or
     * {@code null} when the repository is not tenant-scoped.
     */
    private final BiPredicate<String, UUID> visibility;

    /**
     * Creates a storage over the given connection and namespace.
     *
     * @param redisDriver      the connection used for every command
     * @param entityRepository the repository serving the entity, constructed
     *                         after its driver has connected
     * @param namespace        the prefix applied to every key this storage owns,
     *                         itself prefixed with the database name
     */
    protected RedisEntityReferenceIdStorage(final RedisDriver redisDriver, final EntityRepository<Entity> entityRepository, final String namespace) {
        super(redisDriver, "%s:%s".formatted(entityRepository.getDatabaseDriver().getDatabaseName(), namespace));

        final String tenantId = entityRepository.getDatabaseDriver().getTenantId();

        this.visibility = entityRepository.isTenantScoped() ? (key, id) -> key.startsWith(this.resolveKey(this.getTenantKey(TenantEntity.SHARED_TENANT_ID, ""))) || key.startsWith(this.resolveKey(this.getTenantKey(tenantId, ""))) : null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Stores the entity's identifier under the key derived from it, prefixed
     * with its tenant for a {@link TenantEntity}. An entity with no key is
     * ignored, since {@link Storage#put(Object, Object)} treats a null key as a
     * no-op.</p>
     */
    @Override
    public void index(final Entity entity) {
        this.put(this.getTenantKey(entity), entity.getId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Removes the mapping for the entity's current key. An entity whose key
     * has already changed no longer resolves to the entry it left behind, which
     * is what {@link Storage#reIndex(Object, Object)} exists to handle.</p>
     */
    @Override
    public void unIndex(final Entity entity) {
        this.remove(this.getTenantKey(entity));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The previous key is given unprefixed, the same form
     * {@link #getKey(io.github.trae.database.entity.Entity)} returns, and is
     * prefixed with the entity's tenant before removal.</p>
     */
    @Override
    public void reIndex(final Entity entity, final String previousKey) {
        this.remove(this.getTenantKey(TenantEntity.resolveTenantId(entity), previousKey));
        this.index(entity);
    }

    /**
     * Returns a key prefixed with a tenant.
     *
     * @param tenantId the tenant, or {@code null} for no prefix
     * @param key      the unprefixed key
     * @return the tenant key, or the key unchanged when the tenant or key is
     * {@code null}
     */
    public final String getTenantKey(final String tenantId, final String key) {
        return tenantId == null || key == null ? key : "%s:%s".formatted(tenantId, key);
    }

    /**
     * Returns the key an entity is filed under, prefixed with its tenant for a
     * {@link TenantEntity}.
     *
     * @param entity the entity to derive a key from
     * @return the tenant key, or {@code null} if the entity has no key
     */
    public final String getTenantKey(final Entity entity) {
        return this.getTenantKey(TenantEntity.resolveTenantId(entity), this.getKey(entity));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String serialize(final UUID value) {
        return value.toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Throws on a value that is not a well-formed identifier, which the read
     * path turns into a miss and evicts rather than passing to the caller.</p>
     */
    @Override
    protected UUID deserialize(final String value) {
        return UUID.fromString(value);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Hides entries filed under another tenant's prefix when the repository
     * is tenant-scoped.</p>
     */
    @Override
    protected final BiPredicate<String, UUID> getVisibility() {
        return this.visibility;
    }

    /**
     * Returns the key this storage files an entity under.
     *
     * @param entity the entity to derive a key from
     * @return the secondary key, or {@code null} if the entity has none
     */
    protected abstract String getKey(final Entity entity);
}