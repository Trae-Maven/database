package io.github.trae.database.storage.interfaces;

import java.time.Duration;
import java.util.function.Predicate;

public interface ILocalStorage<Key, Value> extends Storage<Key, Value> {

    void put(final Key key, final Value value, final Duration ttl, final Predicate<Value> predicate);

    void put(final Key key, final Value value, final Predicate<Value> predicate);

    void update(final Key previousKey, final Key key, final Value value, final Duration ttl, final Predicate<Value> predicate);

    void update(final Key previousKey, final Key key, final Value value, final Predicate<Value> predicate);
}