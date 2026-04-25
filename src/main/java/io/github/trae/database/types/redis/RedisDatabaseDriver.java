package io.github.trae.database.types.redis;

import io.github.trae.database.driver.RedisDriver;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Jedis-backed implementation of the {@link RedisDriver} interface.
 *
 * <p>Manages a {@link JedisPool} connection pool and provides two resource
 * helpers — {@link #useResource} for fire-and-forget writes and
 * {@link #getResource} for reads with a return value. Both automatically
 * acquire and release connections via try-with-resources.</p>
 *
 * @see RedisDriver
 */
@RequiredArgsConstructor
public class RedisDatabaseDriver implements RedisDriver {

    private final JedisPoolConfig jedisPoolConfig;
    private final String address;
    private final int port;
    private final String password;

    @Getter
    private JedisPool jedisPool;

    /**
     * Opens the Jedis connection pool using the configured pool settings,
     * address, port, and password.
     */
    @Override
    public void connect() {
        this.jedisPool = new JedisPool(this.jedisPoolConfig, this.address, this.port, 2000, this.password);
    }

    /**
     * Closes the Jedis connection pool and releases all connections.
     */
    @Override
    public void disconnect() {
        if (this.jedisPool != null) {
            this.jedisPool.close();
        }
    }

    /**
     * Acquires a pooled {@link Jedis} connection, passes it to the consumer,
     * and releases it automatically after execution.
     *
     * @param consumer the operation to execute against the Jedis connection
     */
    @Override
    public void useResource(final Consumer<Jedis> consumer) {
        try (final Jedis jedis = this.getJedisPool().getResource()) {
            consumer.accept(jedis);
        }
    }

    /**
     * Acquires a pooled {@link Jedis} connection, passes it to the function,
     * returns the result, and releases the connection automatically.
     *
     * @param function     the operation to execute against the Jedis connection
     * @param <ReturnType> the return type of the operation
     * @return the result of the function
     */
    @Override
    public <ReturnType> ReturnType getResource(final Function<Jedis, ReturnType> function) {
        try (final Jedis jedis = this.getJedisPool().getResource()) {
            return function.apply(jedis);
        }
    }
}