package io.github.trae.database;

import io.github.trae.database.repository.AbstractRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Central registry for all {@link AbstractRepository} instances in the application.
 *
 * <p>Repositories register themselves via {@link #addRepository} during startup,
 * allowing the API to provide a unified readiness check through
 * {@link #isDatabaseLoaded()}. This is typically used to gate incoming requests
 * or scheduled tasks until all repositories have finished their initial load.</p>
 */
public class DatabaseApi {

    private static final List<AbstractRepository<?, ?>> repositoryList = new ArrayList<>();

    /**
     * Registers a repository with the global registry.
     *
     * <p>Should be called during application initialization for every
     * repository that participates in the readiness check.</p>
     *
     * @param abstractRepository the repository to register
     */
    public static void addRepository(final AbstractRepository<?, ?> abstractRepository) {
        repositoryList.add(abstractRepository);
    }

    /**
     * Returns whether all registered repositories have completed loading.
     *
     * <p>Delegates to {@link AbstractRepository#isLoaded()} on each registered
     * repository. Returns {@code true} only when every repository reports loaded,
     * or when no repositories have been registered.</p>
     *
     * @return {@code true} if all repositories are loaded, {@code false} otherwise
     */
    public static boolean isDatabaseLoaded() {
        return repositoryList.stream().allMatch(AbstractRepository::isLoaded);
    }
}