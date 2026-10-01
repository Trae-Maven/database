package io.github.trae.database.entity;

/**
 * An entity whose rows are scoped to a tenant.
 *
 * <p>Every row of a tenant entity carries a {@code tenant_id}. A row whose tenant
 * is {@link #SHARED_TENANT_ID} is shared by every tenant reading that table, and
 * any other value scopes the row to that one tenant. A tenant sees the shared
 * rows plus its own, and never another tenant's.</p>
 *
 * <p>The reading tenant comes from
 * {@link io.github.trae.database.driver.DatabaseDriver#getTenantId()}. A driver
 * returning {@code null} is standalone and applies no scoping, so the same entity
 * works unchanged on a server that is not part of a tenant group.</p>
 *
 * <p>Scoping is applied by the library: the repository adds the tenant condition
 * to every read and writes the tenant on every save, and the holder filters out
 * shared cache entries belonging to another tenant. Consumer queries never name
 * the tenant themselves.</p>
 *
 * @see io.github.trae.database.repository.EntityRepository
 * @see EntityHolder
 */
public interface TenantEntity extends Entity {

    /**
     * The tenant marking a row as shared by every tenant.
     */
    String SHARED_TENANT_ID = "*";

    /**
     * Returns the tenant this entity belongs to.
     *
     * @return the tenant, {@link #SHARED_TENANT_ID} for a shared entity, or
     * {@code null}, which is persisted as {@link #SHARED_TENANT_ID}
     */
    String getTenantId();

    /**
     * Sets the tenant this entity belongs to.
     *
     * <p>Called by the repository when rebuilding the entity from a row.</p>
     *
     * @param tenantId the tenant
     */
    void setTenantId(final String tenantId);

    /**
     * Returns the tenant an entity is stored under.
     *
     * @param entity the entity to resolve
     * @return the entity's tenant, {@link #SHARED_TENANT_ID} for a tenant entity
     * with none set, or {@code null} if the entity is not a tenant entity
     */
    static String resolveTenantId(final Entity entity) {
        if (!(entity instanceof final TenantEntity tenantEntity)) {
            return null;
        }

        return tenantEntity.getTenantId() == null ? SHARED_TENANT_ID : tenantEntity.getTenantId();
    }

    /**
     * Returns whether a tenant may see an entity.
     *
     * <p>Always true for a standalone reader or a non-tenant entity. Otherwise
     * true only for a shared entity or one belonging to the reading tenant.</p>
     *
     * @param entity   the entity to check
     * @param tenantId the reading tenant, or {@code null} for standalone
     * @return {@code true} if the entity is visible to the tenant
     */
    static boolean isVisible(final Entity entity, final String tenantId) {
        final String entityTenantId = resolveTenantId(entity);

        return tenantId == null || entityTenantId == null || entityTenantId.equals(SHARED_TENANT_ID) || entityTenantId.equals(tenantId);
    }
}