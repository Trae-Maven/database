package io.github.trae.database.driver;

import redis.clients.jedis.Jedis;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Redis driver interface extending {@link Connector} for connection lifecycle management.
 *
 * <p>Provides two resource helpers that automatically acquire and release pooled
 * {@link Jedis} connections via try-with-resources — {@link #useResource} for
 * fire-and-forget writes and {@link #getResource} for reads with a return value.</p>
 *
 * @see Connector
 */
public interface RedisDriver extends Connector {

    /**
     * Acquires a pooled {@link Jedis} connection, passes it to the consumer,
     * and releases it automatically after execution.
     *
     * @param consumer the operation to execute against the Jedis connection
     */
    void useResource(final Consumer<Jedis> consumer);

    /**
     * Acquires a pooled {@link Jedis} connection, passes it to the function,
     * returns the result, and releases the connection automatically.
     *
     * @param function     the operation to execute against the Jedis connection
     * @param <ReturnType> the return type of the operation
     * @return the result of the function
     */
    <ReturnType> ReturnType getResource(final Function<Jedis, ReturnType> function);
}