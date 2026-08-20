package io.github.trae.database.driver;

/**
 * Lifecycle contract shared by every driver in the library.
 *
 * <p>Drivers are constructed with their configuration but hold no connection
 * until {@link #connect()} runs, which lets them be built and wired freely and
 * opened once at startup. {@link #disconnect()} releases everything, and is
 * expected to flush any pending work before it does.</p>
 *
 * @see DatabaseDriver
 * @see RedisDriver
 */
public interface Connector {

    /**
     * Opens the driver's connections and performs any startup work.
     */
    void connect();

    /**
     * Flushes pending work and releases every connection this driver holds.
     */
    void disconnect();
}