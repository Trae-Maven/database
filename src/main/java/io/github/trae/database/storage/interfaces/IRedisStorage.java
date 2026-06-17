package io.github.trae.database.storage.interfaces;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public interface IRedisStorage<Value> extends Storage<String, Value> {

    Optional<Value> getOrLoad(final String key, final Duration ttl, final Supplier<Optional<Value>> loader, final Duration lockTtl, final int maxRetries, final Duration retryDelay);
}