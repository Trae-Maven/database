package io.github.trae.database.driver;

import io.github.trae.utilities.UtilString;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>A driver is connected once and not reused after {@link #disconnect()} — the
 * closed connections are kept rather than nulled, so work still in flight during
 * shutdown fails with Lettuce's own closed-connection error instead of a null
 * dereference.</p>
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
     * The pub/sub connection, opened on first subscription. Volatile because it
     * is written under a lock in {@link #openPubSubConnection()} and read
     * without one.
     */
    private volatile StatefulRedisPubSubConnection<String, String> pubSubConnection;

    /**
     * Creates the client and opens the shared command connection, ignoring a
     * second call so a repeated connect cannot orphan the first client.
     *
     * <p>The pub/sub connection is not opened here — it waits for the first
     * {@link #subscribe(String, Consumer)}.</p>
     */
    @Override
    public void connect() {
        if (this.redisClient != null) {
            return;
        }

        final RedisURI.Builder builder = RedisURI.builder().withHost(this.address).withPort(this.port).withTimeout(Duration.ofMillis(this.timeout));

        if (!UtilString.isEmpty(this.password)) {
            builder.withPassword(this.password.toCharArray());
        }

        this.redisClient = RedisClient.create(builder.build());
        this.connection = this.redisClient.connect();
    }

    /**
     * Closes both connections, shuts the client's event loops down and clears
     * every registered subscriber.
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

        this.subscriberMap.clear();
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
     * Runs an asynchronous command against the shared connection.
     *
     * <p>The returned future completes on Lettuce's event loop, so any
     * non-trivial continuation belongs on {@code thenApplyAsync} with an
     * executor of the caller's choosing rather than {@code thenApply}.</p>
     *
     * @param <Type>   the result type
     * @param function the command to run
     * @return the command's result
     */
    public <Type> CompletableFuture<Type> getAsyncResource(final Function<RedisAsyncCommands<String, String>, RedisFuture<Type>> function) {
        return function.apply(this.asynchronous()).toCompletableFuture();
    }

    /**
     * Registers a subscriber for a channel, opening the pub/sub connection and
     * issuing the {@code SUBSCRIBE} only for the channel's first subscriber.
     *
     * <p>Every channel shares one connection and one listener, which dispatches
     * each message to the subscribers registered for that channel. Subscribers
     * run on Lettuce's event loop, so long or blocking work belongs elsewhere; a
     * subscriber that throws is logged and does not stop the rest from being
     * called.</p>
     *
     * @param channel  the channel to listen on
     * @param consumer called with each message published to that channel
     */
    public void subscribe(final String channel, final Consumer<String> consumer) {
        final AtomicBoolean created = new AtomicBoolean();

        this.subscriberMap.computeIfAbsent(channel, ignored -> {
            created.set(true);
            return new CopyOnWriteArrayList<>();
        }).add(consumer);

        if (!created.get()) {
            return;
        }

        this.openPubSubConnection();

        this.pubSubConnection.sync().subscribe(channel);
    }

    /**
     * Publishes a message to a channel without waiting for delivery, logging a
     * failed publish rather than dropping it.
     *
     * @param channel the channel to publish to
     * @param message the message body
     */
    public void publish(final String channel, final String message) {
        this.asynchronous().publish(channel, message).exceptionally(throwable -> {
            LOGGER.error("Failed to publish to channel {}", channel, throwable);
            return null;
        });
    }

    /**
     * Opens the shared pub/sub connection and attaches the dispatching listener,
     * once.
     *
     * <p>Synchronized so two threads subscribing at the same time cannot each
     * open a connection and leak one of them.</p>
     */
    private synchronized void openPubSubConnection() {
        if (this.pubSubConnection != null) {
            return;
        }

        this.pubSubConnection = this.redisClient.connectPubSub();

        this.pubSubConnection.addListener(new RedisPubSubAdapter<>() {

            @Override
            public void message(final String subscribedChannel, final String message) {
                RedisDriver.this.dispatch(subscribedChannel, message);
            }
        });
    }

    /**
     * Hands a message to every subscriber on a channel, isolating each from the
     * others so one throwing does not skip the rest.
     *
     * @param channel the channel the message arrived on
     * @param message the message body
     */
    private void dispatch(final String channel, final String message) {
        this.subscriberMap.getOrDefault(channel, Collections.emptyList()).forEach(subscriber -> {
            try {
                subscriber.accept(message);
            } catch (final Exception exception) {
                LOGGER.error("Subscriber on channel {} threw", channel, exception);
            }
        });
    }
}