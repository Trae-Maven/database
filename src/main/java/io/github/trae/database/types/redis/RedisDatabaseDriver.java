package io.github.trae.database.types.redis;

import io.github.trae.database.driver.RedisDriver;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Jedis-backed implementation of the {@link RedisDriver} interface.
 *
 * <p>Manages a pooled {@link RedisClient} and provides two helpers —
 * {@link #useResource} for fire-and-forget writes and {@link #getResource}
 * for reads with a return value. {@link RedisClient} is itself the command
 * object and pools connections internally, so callers operate on the client
 * directly rather than borrowing and releasing a connection.</p>
 *
 * @see RedisDriver
 */
@RequiredArgsConstructor
public class RedisDatabaseDriver implements RedisDriver {

    private final ConnectionPoolConfig connectionPoolConfig;
    private final String address;
    private final int port;
    private final String password;

    @Getter
    private RedisClient redisClient;

    /**
     * Opens the pooled {@link RedisClient} using the configured pool settings,
     * address, port, password, and a 2000ms connection/socket timeout.
     */
    @Override
    public void connect() {
        this.redisClient = RedisClient.builder()
                .hostAndPort(this.address, this.port)
                .poolConfig(this.connectionPoolConfig)
                .clientConfig(DefaultJedisClientConfig.builder()
                        .password(this.password)
                        .connectionTimeoutMillis(2000)
                        .socketTimeoutMillis(2000)
                        .build())
                .build();
    }

    /**
     * Closes the {@link RedisClient} and releases all pooled connections.
     */
    @Override
    public void disconnect() {
        if (this.redisClient != null) {
            this.redisClient.close();
        }
    }

    /**
     * Passes the pooled client to the consumer. Connection acquisition and
     * release are handled internally by {@link RedisClient}.
     *
     * @param consumer the operation to execute against the client
     */
    @Override
    public void useResource(final Consumer<UnifiedJedis> consumer) {
        consumer.accept(this.getRedisClient());
    }

    /**
     * Passes the pooled client to the function and returns its result.
     * Connection acquisition and release are handled internally by
     * {@link RedisClient}.
     *
     * @param function the operation to execute against the client
     * @param <Type>   the return type of the operation
     * @return the result of the function
     */
    @Override
    public <Type> Type getResource(final Function<UnifiedJedis, Type> function) {
        return function.apply(this.getRedisClient());
    }
}