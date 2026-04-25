package io.github.trae.database.driver;

public interface Connector {

    /**
     * Opens the connection to the database.
     */
    void connect();

    /**
     * Flushes any pending batched operations and closes the database connection.
     */
    void disconnect();
}