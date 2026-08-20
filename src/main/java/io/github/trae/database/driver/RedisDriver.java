package io.github.trae.database.driver;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import lombok.CustomLog;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lettuce-backed Redis connection, used both as a cache tier and as a message
 * bus.
 *
 * <p>Lettuce connections are thread-safe and multiplexed, so a single
 * {@link StatefulRedisConnection} serves every caller — there is no pool to
 * size or borrow from. Commands are reached through {@link #synchronous()} or
 * {@link #asynchronous()}, with {@link #useResource(Consumer)} and
 * {@link #getResource(Function)} available for grouping several commands into
 * one lambda.</p>
 *
 * <p>Pub/sub needs its own connection, since a subscribed connection cannot run
 * ordinary commands. That one is opened lazily on the first
 * {@link #subscribe(String, Consumer)} and shared by every channel, with
 * per-channel subscribers held in a map and dispatched by a single listener.
 * The usual use is cross-server cache invalidation: whichever server writes an
 * entity publishes its identifier, and the rest evict their copy.</p>
 *
 * <p>The configured timeout is Lettuce's <em>command</em> timeout, not a connect
 * timeout — every command waits at most this long before failing. Keep it short
 * when commands run on a latency-sensitive thread, since an unreachable Redis
 * otherwise blocks that thread for the full duration.</p>
 */
@CustomLog
@RequiredArgsConstructor
public class RedisDriver implements Connector {

    /**
     * Redis host.
     */
    private final String address;

    /**
     * Redis port.
     */
    private final int port;

    /**
     * Redis password, or empty for an unauthenticated server.
     */
    private final String password;

    /**
     * Command timeout in milliseconds.
     */
    private final long timeout;

    /**
     * Registered subscribers by channel. Values are copy-on-write so dispatch
     * never blocks a subscribe.
     */
    private final Map<String, List<Consumer<String>>> subscriberMap = new ConcurrentHashMap<>();

    /**
     * The Lettuce client, created on {@link #connect()}.
     */
    @Getter
    private RedisClient redisClient;

    /**
     * The shared command connection.
     */
    private StatefulRedisConnection<String, String> connection;

    /**
     * The pub/sub connection, opened on first subscription.
     */
    private StatefulRedisPubSubConnection<String, String> pubSubConnection;

    /**
     * Creates the client and opens the shared command connection.
     *
     * <p>The pub/sub connection is not opened here — it waits for the first
     * {@link #subscribe(String, Consumer)}.</p>
     */
    @Override
    public void connect() {
        final RedisURI.Builder builder = RedisURI.builder().withHost(this.address).withPort(this.port).withTimeout(Duration.ofMillis(this.timeout));

        if (this.password != null && !this.password.isEmpty()) {
            builder.withPassword(this.password.toCharArray());
        }

        this.redisClient = RedisClient.create(builder.build());
        this.connection = this.redisClient.connect();
    }

    /**
     * Closes both connections and shuts the client's event loops down.
     */
    @Override
    public void disconnect() {
        if (this.pubSubConnection != null) {
            this.pubSubConnection.close();
        }

        if (this.connection != null) {
            this.connection.close();
        }

        if (this.redisClient != null) {
            this.redisClient.shutdown();
        }
    }

    /**
     * Returns blocking commands on the shared connection.
     *
     * @return the synchronous command interface
     */
    public RedisCommands<String, String> synchronous() {
        return this.connection.sync();
    }

    /**
     * Returns future-returning commands on the shared connection.
     *
     * @return the asynchronous command interface
     */
    public RedisAsyncCommands<String, String> asynchronous() {
        return this.connection.async();
    }

    /**
     * Runs several blocking commands against the shared connection.
     *
     * @param consumer the commands to run
     */
    public void useResource(final Consumer<RedisCommands<String, String>> consumer) {
        consumer.accept(this.synchronous());
    }

    /**
     * Runs blocking commands against the shared connection and returns a result.
     *
     * @param <Type>   the result type
     * @param function the commands to run
     * @return whatever the function produced
     */
    public <Type> Type getResource(final Function<RedisCommands<String, String>, Type> function) {
        return function.apply(this.synchronous());
    }

    /**
     * Runs asynchronous commands against the shared connection and returns a
     * result.
     *
     * @param <Type>   the result type
     * @param function the commands to run
     * @return whatever the function produced
     */
    public <Type> Type getAsyncResource(final Function<RedisAsyncCommands<String, String>, Type> function) {
        return function.apply(this.asynchronous());
    }

    /**
     * Registers a subscriber for a channel, opening the pub/sub connection if
     * this is the first subscription.
     *
     * <p>Every channel shares one connection and one listener, which dispatches
     * each message to the subscribers registered for that channel. Subscribers
     * run on Lettuce's event loop, so long or blocking work belongs elsewhere.</p>
     *
     * @param channel  the channel to listen on
     * @param consumer called with each message published to that channel
     */
    public void subscribe(final String channel, final Consumer<String> consumer) {
        this.subscriberMap.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(consumer);

        if (this.pubSubConnection == null) {
            this.pubSubConnection = this.redisClient.connectPubSub();

            this.pubSubConnection.addListener(new RedisPubSubAdapter<>() {

                @Override
                public void message(final String channel, final String message) {
                    RedisDriver.this.subscriberMap.getOrDefault(channel, List.of()).forEach(subscriber -> subscriber.accept(message));
                }
            });
        }

        this.pubSubConnection.sync().subscribe(channel);
    }

    /**
     * Publishes a message to a channel without waiting for delivery.
     *
     * @param channel the channel to publish to
     * @param message the message body
     */
    public void publish(final String channel, final String message) {
        this.asynchronous().publish(channel, message);
    }
}