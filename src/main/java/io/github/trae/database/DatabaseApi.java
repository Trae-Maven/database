package io.github.trae.database;

import io.github.trae.database.repository.EntityRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static registry of every repository built in this application.
 *
 * <p>A repository adds itself here from its constructor, before any connection
 * exists. {@link io.github.trae.database.driver.DatabaseDriver#connect()} then
 * walks the registry to create and migrate each table and its indexes — which is
 * why repositories must be constructed before the driver connects, and why the
 * registry is static rather than held on the driver: a repository needs
 * somewhere to register at construction time, when the driver may not have been
 * built yet.</p>
 *
 * <p>{@link #isDatabaseLoaded()} gives the application one flag to gate on,
 * reporting true only once every registered repository has been marked loaded.</p>
 *
 * <p>Registration happens once per repository during startup, on whichever thread
 * the container builds components on. The list is not synchronised, so
 * repositories should not be constructed concurrently or lazily from request
 * threads.</p>
 *
 * @see EntityRepository
 * @see io.github.trae.database.driver.DatabaseDriver
 */
public class DatabaseApi {

    /**
     * Every repository constructed so far, in construction order.
     */
    private static final List<EntityRepository<?>> REPOSITORY_LIST = new ArrayList<>();

    /**
     * Returns every registered repository.
     *
     * @return an unmodifiable view of the registry, in construction order
     */
    public static List<EntityRepository<?>> getRepositoryList() {
        return Collections.unmodifiableList(REPOSITORY_LIST);
    }

    /**
     * Registers a repository.
     *
     * <p>Called by {@link EntityRepository}'s constructor — there is no need to
     * call it directly.</p>
     *
     * @param entityRepository the repository to register
     */
    public static void addRepository(final EntityRepository<?> entityRepository) {
        REPOSITORY_LIST.add(entityRepository);
    }

    /**
     * Returns whether every registered repository has finished loading.
     *
     * <p>Vacuously true before any repository has been constructed, so it is only
     * meaningful once the container has finished wiring.</p>
     *
     * @return {@code true} if every repository is marked loaded
     */
    public static boolean isDatabaseLoaded() {
        return REPOSITORY_LIST.stream().allMatch(EntityRepository::isLoaded);
    }
}