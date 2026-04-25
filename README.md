# Database

A unified database abstraction layer providing annotation-driven domain mapping, repository-based CRUD operations, local/remote storage with TTL support, and multi-backend support for MongoDB, MySQL, and Redis.

Database eliminates boilerplate by handling serialization, deserialization, batched writes, filtering, indexing, and async operations behind a single `DatabaseDriver` interface. Define a domain, annotate a repository, and the framework does the rest.

---

## Features

- **Domain mapping** — define persistable entities with a property enum and a `DomainData` constructor; the framework handles all serialization and deserialization
- **Repository pattern** — extend `AbstractRepository` for zero-boilerplate CRUD, sync/async reads, exists, count, and index management
- **Filter-based write matching** — override `getFiltersByDomain` to upsert, update, and delete by compound field conditions instead of `_id`, enabling one-doc-per-combination patterns (e.g. one wishlist entry per user per product)
- **Universal filter system** — fluent `FilterBuilder` with operators (equals, greater than, in, regex, exists, etc.) that translate to native queries on any backend
- **Query options** — sort, limit, and skip via `QueryOptions` for paginated and ordered queries
- **Index management** — declare single and compound indexes with `.on()` chaining and `.unique()`, applied identically across MongoDB and MySQL
- **Batched writes** — generic `BatchQueue<T>` with `ReentrantLock`-based thread safety, configurable batch size and flush interval, instant mode, and graceful shutdown with 30s termination timeout
- **MongoDB driver** — grouped `bulkWrite` per collection, `_id` as UUID primary key, full filter/sort/index translation to native BSON
- **MySQL driver** — HikariCP connection pooling, transactional batch execution, automatic `CREATE DATABASE`/`CREATE TABLE`, parameterized queries throughout
- **Redis driver** — Jedis-backed connection pooling with `useResource`/`getResource` helpers for clean resource management
- **Local storage** — `ConcurrentHashMap`-backed in-memory key-value storage with per-key TTL, lazy expiry eviction on reads, and batched background cleanup
- **Redis storage** — Jedis-backed key-value storage with native `SETEX` TTL, `SCAN`-based iteration, and `MGET` batch retrieval
- **Storage interface** — unified `Storage<Key, Value>` contract shared by both `LocalStorage` and `RedisStorage`, enabling drop-in swaps between local and remote caching

---

## Requirements

Your project must already include the following dependencies:
```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.44</version>
    <scope>provided</scope>
</dependency>
```

These dependencies are marked as **provided** inside Database because they are expected to already exist in your application.

---

## Built-in Dependencies

Database includes several dependencies that are automatically included when you install the library.

- [Utilities](https://github.com/Trae-Maven/utilities) – Shared helper classes and performance-focused utilities used internally by the framework.
- [Dependency-Injector](https://github.com/Trae-Maven/dependency-injector) – Component scanning and injection; `@Repository` is meta-annotated with `@Component` for automatic discovery.

**MongoDB backend:**
```xml
<dependency>
    <groupId>org.mongodb</groupId>
    <artifactId>mongodb-driver-sync</artifactId>
    <version>5.6.2</version>
</dependency>
```

**MySQL backend:**
```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>7.0.2</version>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>9.6.0</version>
</dependency>
```

**Redis backend:**
```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>5.2.0</version>
</dependency>
```

These dependencies are automatically included when installing Database and do not need to be added manually.

---

## Installation

Add the repository and dependency to your `pom.xml`:
```xml
<repository>
    <id>github-database</id>
    <url>https://maven.pkg.github.com/Trae-Maven/database</url>
</repository>
```

```xml
<dependency>
    <groupId>io.github.trae.database</groupId>
    <artifactId>Database</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## Integration Guide

Database requires three classes to be set up in your application: a property enum, a domain class, and a repository. An optional manager service handles your business logic.

### 1. Define Your Property Enum

Create an enum that implements `DomainProperty`. Each constant represents a persistable field on the domain:
```java
public enum AccountProperty implements DomainProperty {

    EMAIL, USERNAME, PASSWORD
}
```

### 2. Define Your Domain

Your domain class must implement `Domain<Property>` with a `DomainData` constructor for deserialization and a `getValueByProperty` switch for serialization:
```java
@RequiredArgsConstructor
@Getter
@Setter
public class Account implements Domain<AccountProperty> {

    private final UUID id;

    private String email, username, password;

    public Account(final DomainData<AccountProperty> domainData) {
        this(domainData.getIdentifier());

        this.email = domainData.get(String.class, AccountProperty.EMAIL);
        this.username = domainData.get(String.class, AccountProperty.USERNAME);
        this.password = domainData.get(String.class, AccountProperty.PASSWORD);
    }

    @Override
    public Object getValueByProperty(final AccountProperty accountProperty) {
        return switch (accountProperty) {
            case EMAIL -> this.getEmail();
            case USERNAME -> this.getUsername();
            case PASSWORD -> this.getPassword();
        };
    }
}
```

### 3. Create Your Repository

Extend `AbstractRepository` and annotate with `@Repository`. All CRUD, filtering, and async operations are inherited — zero boilerplate. Override `registerIndexes()` to declare indexes:
```java
@Repository(databaseName = "Admin", collectionName = "Accounts")
public class AccountRepository extends AbstractRepository<Account, AccountProperty> {

    public AccountRepository(final DatabaseDriver databaseDriver) {
        super(databaseDriver);
    }

    @Override
    public void registerIndexes() {
        this.addIndex(new Index().on(AccountProperty.EMAIL.name(), SortDirection.ASCENDING).unique());
        this.addIndex(new Index().on(AccountProperty.USERNAME.name(), SortDirection.ASCENDING).unique());
    }
}
```

---

## Storage

The `Storage<Key, Value>` interface provides a unified contract for key-value storage with TTL support. Two implementations are included: `LocalStorage` for in-memory caching and `RedisStorage` for distributed caching via Redis.

Both share the same interface, so you can swap between local and remote storage without changing your business logic.

### Storage Interface

```java
public interface Storage<Key, Value> {

    void put(Key key, Value value, Duration ttl);

    void remove(Key key);

    void update(Key previousKey, Key key, Value value, Duration ttl);

    Optional<Value> get(Key key);

    boolean contains(Key key);

    void flush();

    List<Key> getKeys();

    List<Value> getValues();

    int getSize();

    boolean isEmpty();

    void index(Value value);

    void unIndex(Value value);
}
```

### LocalStorage

`ConcurrentHashMap`-backed in-memory storage with per-key TTL. Each entry is wrapped in a `Cache<Value>` object that tracks its creation time and TTL duration.

**Expiry behavior:**

- On `get()` — if the entry has expired, it is lazily removed and `Optional.empty()` is returned
- On `getKeys()`, `getValues()`, `getSize()` — expired entries are filtered out of results
- **Background eviction** — every 60 seconds (triggered on the next `get()` call), a sweep removes up to 10,000 expired entries per pass to prevent memory buildup without causing lag spikes. If more than 10,000 expired entries exist, the sweep continues on the next `get()` call immediately until all expired entries are cleaned
- Passing a `null` TTL to `Cache` makes the entry permanent — it never expires

```java
public class ClanIdStorage extends LocalStorage<UUID, Clan> {

    @Override
    public void index(final Clan clan) {
        this.put(clan.getId(), clan, null);  // permanent
    }

    @Override
    public void unIndex(final Clan clan) {
        this.remove(clan.getId());
    }
}
```

```java
public class SessionStorage extends LocalStorage<UUID, Session> {

    @Override
    public void index(final Session session) {
        this.put(session.getId(), session, Duration.ofMinutes(30));  // expires in 30 minutes
    }

    @Override
    public void unIndex(final Session session) {
        this.remove(session.getId());
    }
}
```

#### Cache

The `Cache<Value>` wrapper holds the stored value alongside its TTL and creation timestamp:

```java
@AllArgsConstructor
@Getter
public class Cache<Value> implements ICache {

    private final Value value;
    private final Duration ttl;
    private final long systemTime = System.currentTimeMillis();

    @Override
    public boolean isValid() {
        if (this.getTtl() == null) {
            return true;  // permanent entry
        }

        return !(UtilTime.elapsed(this.getSystemTime(), this.getTtl().toMillis()));
    }
}
```

| Field | Purpose |
|---|---|
| `value` | The stored object |
| `ttl` | Time-to-live duration, or `null` for permanent entries |
| `systemTime` | Millisecond timestamp captured at construction via `System.currentTimeMillis()` |
| `isValid()` | Returns `true` if the TTL is `null` (permanent) or the elapsed time since creation has not exceeded the TTL |

### RedisStorage

Jedis-backed distributed storage with native Redis TTL via `SETEX`. Keys are automatically prefixed with a configurable namespace to avoid collisions. The `Value` type is resolved at runtime via `UtilGeneric` — no need to pass the class explicitly.

**Key format:** `{redisKey}:{key}` — e.g. `clan:id:550e8400-e29b-41d4-a716-446655440000`

**Operations:**

| Method | Redis Command |
|---|---|
| `put` | `SETEX` |
| `remove` | `DEL` |
| `get` | `GET` + Gson deserialization |
| `contains` | `EXISTS` |
| `getKeys` | `SCAN` with prefix stripping |
| `getValues` | `SCAN` + `MGET` batch retrieval |
| `getSize` | `SCAN` count |
| `flush` | `SCAN` + batch `DEL` |

All scan-based operations use `SCAN` with a batch count of 100 instead of `KEYS` to avoid blocking the Redis server.

```java
public class ClanIdRedisStorage extends RedisStorage<Clan> {

    public ClanIdRedisStorage(final RedisDatabaseDriver redisDatabaseDriver) {
        super(redisDatabaseDriver, "clan:id");
    }

    @Override
    public void index(final Clan clan) {
        this.put(clan.getId().toString(), clan, Duration.ofHours(1));
    }

    @Override
    public void unIndex(final Clan clan) {
        this.remove(clan.getId().toString());
    }
}
```

### Local vs Redis Storage

| | LocalStorage | RedisStorage |
|---|---|---|
| **Backing store** | `ConcurrentHashMap` | Redis via Jedis |
| **TTL mechanism** | `Cache` wrapper with lazy eviction + batched background sweep | Native Redis `SETEX` |
| **Key type** | Any object | `String` |
| **Serialization** | None (stores Java objects directly) | Gson JSON |
| **Scope** | Single JVM instance | Shared across all instances |
| **Eviction** | Lazy on `get()` + batched sweep (10k/pass, every 60s) | Handled by Redis automatically |
| **Use case** | Hot data, same-instance caching | Distributed caching, cross-instance state |

---

## Filter-Based Write Matching

By default, all write operations (save, update, delete) match documents by their `_id` field. Override `getFiltersByDomain` in your repository to match on a compound set of fields instead. This enables patterns where uniqueness is defined by a combination of fields rather than a single UUID.

### Example: One Wishlist Entry Per User Per Product

A user can wishlist many products, but only once per product. Saving the same combination again updates the existing entry (e.g. refreshing the timestamp) rather than creating a duplicate:

```java
@Repository(databaseName = "Shop", collectionName = "Wishlists")
public class WishlistRepository extends AbstractRepository<WishlistEntry, WishlistProperty> {

    public WishlistRepository(final DatabaseDriver databaseDriver) {
        super(databaseDriver);
    }

    @Override
    public List<Filter> getFiltersByDomain(final WishlistEntry entry) {
        return List.of(
                Filter.eq(WishlistProperty.USER_ID.name(), entry.getUserId()),
                Filter.eq(WishlistProperty.PRODUCT_ID.name(), entry.getProductId())
        );
    }

    @Override
    public void registerIndexes() {
        this.addIndex(new Index()
                .on(WishlistProperty.USER_ID.name(), SortDirection.ASCENDING)
                .on(WishlistProperty.PRODUCT_ID.name(), SortDirection.ASCENDING)
                .unique()
        );
    }
}
```

When `save(entry)` is called, the driver matches on `USER_ID + PRODUCT_ID` instead of `_id`:
- **If a document with that combination exists** — its fields are updated
- **If no document matches** — a new document is inserted with a generated `_id`

This works identically on both backends:
- **MongoDB** — the filter list is compiled into a compound `Filters.and(...)` used as the match condition on the `UpdateOneModel` with upsert
- **MySQL** — `INSERT ... ON DUPLICATE KEY UPDATE` resolves conflicts via the compound unique index declared in `registerIndexes()`

### Example: One Enrolment Per Student Per Course

A student can enrol in many courses, but only once per course. Subsequent saves update the enrolment status rather than duplicating:

```java
@Repository(databaseName = "University", collectionName = "Enrolments")
public class EnrolmentRepository extends AbstractRepository<Enrolment, EnrolmentProperty> {

    public EnrolmentRepository(final DatabaseDriver databaseDriver) {
        super(databaseDriver);
    }

    @Override
    public List<Filter> getFiltersByDomain(final Enrolment enrolment) {
        return List.of(
                Filter.eq(EnrolmentProperty.STUDENT_ID.name(), enrolment.getStudentId()),
                Filter.eq(EnrolmentProperty.COURSE_ID.name(), enrolment.getCourseId())
        );
    }

    @Override
    public void registerIndexes() {
        this.addIndex(new Index()
                .on(EnrolmentProperty.STUDENT_ID.name(), SortDirection.ASCENDING)
                .on(EnrolmentProperty.COURSE_ID.name(), SortDirection.ASCENDING)
                .unique()
        );
    }
}
```

Each student has one enrolment per course. Calling `save(enrolment)` upserts by the compound key, and `delete(enrolment)` removes that specific enrolment without affecting the student's other courses.

### When to Use

| Pattern | `getFiltersByDomain` | Example |
|---|---|---|
| One doc per entity (default) | Not overridden — matches on `_id` | Accounts, Products, Orders |
| One doc per combination | Returns compound filters | Wishlists (user + product), Enrolments (student + course), Subscriptions (user + plan) |

**Important:** When using filter-based matching, always declare a matching compound unique index in `registerIndexes()`. On MongoDB the filters handle the match directly, but on MySQL the `ON DUPLICATE KEY UPDATE` mechanism relies on the unique index to detect conflicts.

---

## Driver Configuration

### MongoDB

```java
DatabaseDriver driver = new MongoDatabaseDriver(
        "mongodb://localhost:27017",  // connection string
        100,                          // batch size
        Duration.ofSeconds(5)         // flush interval (Duration.ZERO for instant)
);

driver.connect();
```

All writes produce `WriteModel` instances collected by the `BatchQueue`. On flush, operations are grouped by `database.collection` and executed as a single `bulkWrite` per collection — one round trip regardless of batch size.

### MySQL

```java
DatabaseDriver driver = new MySqlDatabaseDriver(
        "localhost",   // host
        3306,          // port
        "root",        // username
        "password",    // password
        100,           // batch size
        Duration.ofSeconds(5)  // flush interval (Duration.ZERO for instant)
);

driver.connect();
```

HikariCP connection pool with prepared statement caching and server-side prepared statements. Writes are grouped by database and executed within a single transaction per group. Tables and databases are created automatically on first write.

### Redis

```java
RedisDatabaseDriver redisDriver = new RedisDatabaseDriver("localhost", 6379, "password");

redisDriver.connect();
```

Jedis connection pool with configurable host, port, and password. Resource management is handled via `useResource` and `getResource` helpers that automatically acquire and release connections:

```java
// Fire-and-forget write
redisDriver.useResource(jedis -> jedis.set("key", "value"));

// Read with return value
String value = redisDriver.getResource(jedis -> jedis.get("key"));
```

---

## Filter System

Build filters with the fluent `FilterBuilder` API. All filters are combined with AND semantics and translate to native queries on any backend:
```java
// Simple filter
List<Filter> filters = FilterBuilder.create()
        .equals(AccountProperty.USERNAME.name(), "Trae")
        .build();

// Complex filter
List<Filter> filters = FilterBuilder.create()
        .equals(PlayerProperty.ACTIVE.name(), true)
        .greaterThan(PlayerProperty.KILLS.name(), 10)
        .regex(PlayerProperty.EMAIL.name(), ".*@gmail\\.com$")
        .in(PlayerProperty.STATUS.name(), List.of("ONLINE", "AWAY"))
        .build();

// With query options (sort, limit, skip)
QueryOptions options = QueryOptions.of(filters)
        .sort(PlayerProperty.CREATED_AT.name(), SortDirection.DESCENDING)
        .limit(10)
        .skip(20);

List<Player> players = playerRepository.findManySynchronously(options);
```

**Supported operators:**

| Operator | MongoDB | MySQL |
|---|---|---|
| EQUALS | `$eq` | `= ?` |
| NOT_EQUALS | `$ne` | `!= ?` |
| GREATER_THAN | `$gt` | `> ?` |
| GREATER_THAN_OR_EQUALS | `$gte` | `>= ?` |
| LESS_THAN | `$lt` | `< ?` |
| LESS_THAN_OR_EQUALS | `$lte` | `<= ?` |
| IN | `$in` | `IN (?, ?, ...)` |
| NOT_IN | `$nin` | `NOT IN (?, ?, ...)` |
| EXISTS | `$exists` | `IS [NOT] NULL` |
| REGEX | `$regex` | `REGEXP ?` |

---

## Index Declaration

Declare indexes in `registerIndexes()` using the fluent `.on()` API. Indexes are applied identically across MongoDB and MySQL:
```java
@Override
public void registerIndexes() {
    // Single unique index
    this.addIndex(new Index().on(AccountProperty.EMAIL.name(), SortDirection.ASCENDING).unique());

    // Single descending index
    this.addIndex(new Index().on(ProductProperty.CREATED_AT.name(), SortDirection.DESCENDING));

    // Compound index
    this.addIndex(new Index()
            .on(ProductProperty.CATEGORY.name(), SortDirection.ASCENDING)
            .on(ProductProperty.AVAILABLE.name(), SortDirection.ASCENDING)
            .on(ProductProperty.PRICE.name(), SortDirection.DESCENDING)
    );

    // Compound index with ownership
    this.addIndex(new Index()
            .on(OrderProperty.CUSTOMER_ID.name(), SortDirection.ASCENDING)
            .on(OrderProperty.PLACED_AT.name(), SortDirection.DESCENDING)
    );
}
```

---

## Batch Queue

The `BatchQueue<T>` provides async batched execution with two modes:

**Instant mode** (`Duration.ZERO`) — flushes immediately on every add. Suitable for real-time operations.

**Batched mode** — collects operations and flushes when the queue reaches the configured batch size or on the scheduled interval. Suitable for high-throughput writes.

```java
// Instant — every write executes immediately (async)
new BatchQueue<>(1, Duration.ZERO, this::executeBatch);

// Batched — flush every 5 seconds or when 100 operations are queued
new BatchQueue<>(100, Duration.ofSeconds(5), this::executeBatch);
```

| Feature | Detail |
|---|---|
| **Thread safety** | `ReentrantLock` guarding all queue access |
| **Async execution** | Fixed thread pool sized to `availableProcessors / 2` with daemon threads |
| **Scheduled flush** | `ScheduledExecutorService` with configurable interval |
| **Graceful shutdown** | Scheduler stop → synchronous final flush on calling thread → executor `awaitTermination` (30s) → force-kill |
| **Idempotent shutdown** | `AtomicBoolean` guard — subsequent calls are no-ops |
| **Post-shutdown rejection** | Operations added after shutdown are logged and rejected |

---

## Architecture

```
Domain (Account)
    ↕ DomainData (intermediate data carrier)
    ↕ LinkedHashMap<String, Object> (raw key-value data)
    ↕ AbstractRepository (mapping, delegation, index management, filter-based matching)
    ↕ DatabaseDriver (backend-agnostic interface)
    ↕ MongoDatabaseDriver / MySqlDatabaseDriver (native driver calls)
    ↕ BatchQueue<T> (async batched execution)

Storage<Key, Value> (unified caching interface)
    ↕ LocalStorage (ConcurrentHashMap + Cache<Value> with per-key TTL)
    ↕ RedisStorage (Jedis + SETEX with native Redis TTL)
```

| Layer | Responsibility |
|---|---|
| **Domain** | Business entity with UUID identity and property-based field access |
| **DomainProperty** | Enum defining the persistable fields on a domain |
| **DomainData** | Intermediate carrier wrapping raw database results for typed access |
| **AbstractRepository** | All CRUD, sync/async reads, exists, count, index management, domain mapping, filter-based write matching |
| **@Repository** | Annotation specifying database and collection names, meta-annotated with `@Component` |
| **DatabaseDriver** | Backend-agnostic interface for all database operations |
| **MongoDatabaseDriver** | MongoDB implementation with `bulkWrite` batching and compound filter support |
| **MySqlDatabaseDriver** | MySQL implementation with HikariCP, transactional batching, and unique index conflict resolution |
| **RedisDatabaseDriver** | Redis implementation with Jedis connection pooling and `useResource`/`getResource` helpers |
| **Storage** | Unified key-value storage interface with TTL support, `index`/`unIndex` for domain-aware subclassing |
| **LocalStorage** | In-memory `ConcurrentHashMap` storage with `Cache` wrapper, lazy expiry, and batched eviction sweep |
| **RedisStorage** | Distributed Redis storage with `SETEX` TTL, `SCAN`-based iteration, and `MGET` batch retrieval |
| **Cache** | TTL wrapper holding value, duration, and creation timestamp with `isValid()` expiry check |
| **BatchQueue** | Generic async batch queue with configurable flush strategy |
| **FilterBuilder** | Fluent API for building universal filter conditions |
| **QueryOptions** | Sort, limit, skip wrapper for paginated queries |
| **Index** | Universal index definition with `.on()` chaining |
