package io.github.trae.database.storage.interfaces;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public interface Storage<Key, Value> {

    void put(final Key key, final Value value, final Duration ttl);

    default void put(final Key key, final Value value) {
        this.put(key, value, null);
    }

    void remove(final Key key);

    void update(final Key previousKey, final Key key, final Value value, final Duration ttl);

    default void update(final Key previousKey, final Key key, final Value value) {
        this.update(previousKey, key, value, null);
    }

    Optional<Value> get(final Key key);

    boolean contains(final Key key);

    void flush();

    List<Key> getKeys();

    List<Value> getValues();

    int getSize();

    boolean isEmpty();

    void index(final Value value);

    void unIndex(final Value value);
}