package io.github.trae.database.batch.interfaces;

public interface IBatchQueue<T> {

    void add(final T operation);

    void flush();

    void shutdown();

    int pending();
}