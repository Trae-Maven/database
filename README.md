# Database

A PostgreSQL data-access library built on jOOQ, with declarative property mapping, deferred batched writes, and tiered local → Redis → database lookups with request coalescing.

Database removes the boilerplate around persistence. Declare an entity's columns once as property constants, extend a repository, and you get schema creation, migration, indexes, reads, writes and caching without writing a query or a mapper.

---

## Features

- **Property-driven mapping** — declare each column once as an `EntityProperty` constant holding its name, getter, setter and SQL type; the framework derives the schema, the reads and the writes from that
- **Repository pattern** — extend `EntityRepository` for CRUD, condition and property-based finders, paging, existence checks and counts, with no per-entity query code
- **Schema management** — `createTable`, `migrateSchema`, `dropTable` and `createIndexes` generated from the registered properties and run automatically when the driver connects
- **Index declaration** — override `getIndexes()` to map properties to a `BTREE`, `GIN_TRGM` or `BRIN` index; the `pg_trgm` extension is installed on connect
- **Value converters** — store any Java type in any column type through a `ValueConverter`; enum and JSON converters are built in, and conversion happens transparently on both read and write
- **Deferred batched writes** — every write goes through a `BatchQueue` that coalesces writes to the same entity, orders them by arrival, and commits them in chunked transactions on its own thread
- **Statement batching** — runs of identical SQL within a transaction execute as a single JDBC batch, which pgjdbc rewrites into one multi-row statement
- **Tiered lookups** — `LookupProvider` walks local storage, then Redis, then the database, caching what it finds on the way back
- **Request coalescing** — concurrent identical lookups share one piece of work instead of stampeding the database, with synchronous and asynchronous callers joining the same in-flight request
- **Virtual thread execution** — lookups run on virtual threads, so blocking JDBC and Redis calls never occupy a platform thread or a fixed pool
- **Local storage** — `ConcurrentHashMap`-backed cache with per-storage TTL, lazy eviction on read, a periodic sweep and an optional size cap, with no background scheduler
- **Redis storage** — Lettuce-backed distributed cache with native TTL, a per-namespace key index enabling iteration without `SCAN`, chunked `MGET` retrieval and automatic eviction of values that no longer decode
- **Reference storages** — a secondary key maps to an entity's identifier rather than a second copy of the entity, so one cached entity serves every path to it
- **Redis pub/sub** — publish and subscribe on the same driver, for cross-server cache invalidation on a multi-instance deployment
- **Framework-agnostic** — no Spring or dependency-injection annotations anywhere in the library; annotate your own classes for whichever container you use

---

## Requirements

Your project must already include the following dependency:

```xml
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.18.46</version>
    <scope>provided</scope>
</dependency>
```

Lombok is marked as **provided** inside Database because it is expected to already exist in your application.

Java 21 or later is required — the library uses virtual threads and sequenced collections.

---

## Built-in Dependencies

These are pulled in automatically when you install Database and do not need to be added manually.

- [Utilities](https://github.com/Trae-Maven/utilities) — shared helper classes used internally by the framework
- `org.jooq:jooq` — SQL construction, type binding and value conversion
- `org.postgresql:postgresql` — the PostgreSQL JDBC driver
- `com.zaxxer:HikariCP` — connection pooling
- `io.lettuce:lettuce-core` — Redis client
- `com.google.code.gson:gson` — JSON encoding for the JSON value converter

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
    <groupId>io.github.trae</groupId>
    <artifactId>database</artifactId>
    <version>0.0.1</version>
</dependency>
```

---

## Integration Guide

Per entity: the entity itself, a property holder, and a repository. Add a storage per cached key and an `EntityHolder` manager on top for tiered lookups.

### 1. Define Your Entity

Implement `Entity` and declare a constructor taking the identifier — the repository uses it to rebuild entities from result rows.

```java
@RequiredArgsConstructor
@Getter
@Setter
public class Account implements Entity {

    private final UUID id;

    private String email, password;

    private AccountRole role;

    private RefreshToken refreshToken;

    private long createdAt;
}
```

### 2. Declare Your Properties

Each constant binds one column to its getter, setter and SQL type. This is the only place a column name appears.

```java
public class AccountProperty {

    public static final EntityProperty<Account, String> EMAIL = EntityProperty.register(
            Account.class,
            "email",
            Account::getEmail,
            Account::setEmail,
            SQLDataType.VARCHAR
    );

    public static final EntityProperty<Account, String> PASSWORD = EntityProperty.register(
            Account.class,
            "password",
            Account::getPassword,
            Account::setPassword,
            SQLDataType.VARCHAR
    );

    public static final EntityProperty<Account, Long> CREATED_AT = EntityProperty.register(
            Account.class,
            "createdAt",
            Account::getCreatedAt,
            Account::setCreatedAt,
            SQLDataType.BIGINT
    );

    public static final EntityProperty<Account, AccountRole> ROLE = EntityProperty.registerWithConverter(
            Account.class,
            "role",
            Account::getRole,
            Account::setRole,
            new EnumValueConverter<>(AccountRole.class)
    );

    public static final EntityProperty<Account, RefreshToken> REFRESH_TOKEN = EntityProperty.registerWithConverter(
            Account.class,
            "refreshToken",
            Account::getRefreshToken,
            Account::setRefreshToken,
            new JsonValueConverter<>(RefreshToken.class)
    );
}
```

Registration happens in the holder's static initialiser, so the class must be loaded before the repository does any schema or read work. Referencing any one constant is enough.

### 3. Create Your Repository

Extend `EntityRepository`, passing the entity class and table name. Everything else is inherited. Override `getIndexes()` to declare indexes.

If you're using Spring Boot or [dependency-injector](https://github.com/Trae-Maven/dependency-injector) for component scanning, annotate the class with `@Repository`:

```java
@Repository
public class AccountRepository extends EntityRepository<Account> {

    public AccountRepository(final MyDatabaseDriver databaseDriver) {
        super(databaseDriver, Account.class, "Accounts");
    }

    @Override
    protected Map<EntityProperty<Account, ?>, IndexType> getIndexes() {
        return Map.of(AccountProperty.EMAIL, IndexType.BTREE);
    }

    public Optional<Account> findByEmail(final String email) {
        return this.findOne(AccountProperty.EMAIL, email);
    }
}
```

The repository registers itself with `DatabaseApi` on construction. Build every repository first, then call `connect()` — that is when tables, columns and indexes are brought up to date.

### 4. Add Cached Lookups

Implement `EntityHolder` on your manager to get tiered, coalesced lookups by identifier for free.

```java
@Service
@RequiredArgsConstructor
@Getter
public class AccountManager implements EntityHolder<Account, AccountRepository> {

    private final AccountRepository repository;
    private final AccountIdLocalStorage idLocalStorage;
    private final AccountIdRedisStorage idRedisStorage;

    private final AccountEmailLocalStorage emailLocalStorage;
    private final AccountEmailRedisStorage emailRedisStorage;

    private final LookupProvider<Account> lookupProvider = new LookupProvider<>(this);

    @Override
    public void cacheEntity(final Account account) {
        this.idLocalStorage.index(account);
        this.idRedisStorage.index(account);
    }

    @Override
    public void evictEntity(final Account account) {
        this.idLocalStorage.unIndex(account);
        this.idRedisStorage.unIndex(account);
    }
}
```

`@Getter` satisfies `getRepository()`, `getIdLocalStorage()`, `getIdRedisStorage()` and `getLookupProvider()`, so the only methods left to write are the two cache hooks.

```java
final Optional<Account> accountOptional = accountManager.getEntityByIdSynchronously(id);

accountManager.getEntityByIdAsynchronously(id).thenAccept(accountOptional -> accountOptional.ifPresent(this::handle));

// Existence is just the lookup you were going to run anyway
final boolean exists = accountManager.getEntityByIdSynchronously(id).isPresent();
```

There is no separate existence check, because a caller who has one almost always wants the entity a line later — and a lookup leaves it cached where a bare `EXISTS` would not. Call `repository.exists(id)` directly for the rare check that should not warm the caches.

A lookup by anything other than the identifier runs in two legs — the secondary key resolves to an identifier, and the identifier resolves to the entity. `getEntityByKeySynchronously` and `getEntityByKeyAsynchronously` compose both, so a manager wraps them once per key it supports:

```java
public Optional<Account> getEntityByEmailSynchronously(final String email) {
    return this.getEntityByKeySynchronously("email", email, this.emailLocalStorage, this.emailRedisStorage, this.getRepository()::findIdByEmail);
}

public CompletableFuture<Optional<Account>> getEntityByEmailAsynchronously(final String email) {
    return this.getEntityByKeyAsynchronously("email", email, this.emailLocalStorage, this.emailRedisStorage, this.getRepository()::findIdByEmail);
}
```

Each leg coalesces on its own, so a hundred callers asking by email produce one email lookup and one identifier lookup between them — and the entity itself is cached once, under its identifier, however many keys point at it. The identifier leg usually hits local storage, so the second hop costs a map read rather than a round trip; a fully cold read is the case that pays for both.

---

## Reads and Writes

Reads run immediately. Writes are deferred to the batch queue, so a read issued while a write for the same entity is still queued returns the row as it currently stands in the database.

```java
// Reads
final Optional<Account> account = accountRepository.findById(id);
final Optional<Account> byEmail = accountRepository.findOne(AccountProperty.EMAIL, "trae@example.com");
final List<Account> page = accountRepository.findPage(null, AccountProperty.CREATED_AT.getField().desc(), 0, 25);
final boolean taken = accountRepository.exists(AccountProperty.EMAIL, "trae@example.com");
final long total = accountRepository.count();

// Reading one column without building the entity
final Optional<Long> createdAt = accountRepository.findValue(AccountProperty.CREATED_AT, id);

// Writes
accountRepository.save(account);                                    // every column, as an upsert
accountRepository.update(account, AccountProperty.EMAIL);           // one column
accountRepository.update(account, List.of(AccountProperty.EMAIL, AccountProperty.PASSWORD));
accountRepository.delete(account);
```

`update` reads values off the entity as it runs, so apply your setters before calling it.

| Method | Statement |
|---|---|
| `save` | `INSERT ... ON CONFLICT (id) DO UPDATE` |
| `update` | `UPDATE ... SET <named columns> WHERE id = ?` |
| `delete` | `DELETE FROM ... WHERE id = ?` |

---

## Value Converters

A `ValueConverter<Value, Stored>` describes how a Java value is stored in a column of a different type. The converter chooses the storage type, and jOOQ applies the conversion on every bind and every fetch — nothing downstream is aware it exists.

Two are built in:

| Converter | Stored as | Use |
|---|---|---|
| `EnumValueConverter` | `VARCHAR` | Enum constants, stored by name so constants can be reordered |
| `JsonValueConverter` | `JSONB` | Nested objects and collections written and read whole |

```java
// A nested object
new JsonValueConverter<>(RefreshToken.class)

// A generic collection — the element type is preserved
JsonValueConverter.ofList(String.class)
```

Writing your own means implementing five methods:

```java
@Getter
public class LocationValueConverter implements ValueConverter<Location, String> {

    private final Class<Location> valueType = Location.class;

    @Override
    public DataType<String> getDataType() {
        return SQLDataType.VARCHAR;
    }

    @Override
    public Class<String> getStoredType() {
        return String.class;
    }

    @Override
    public String serialize(final Location value) {
        return "%s,%s,%s,%s".formatted(value.getWorld().getName(), value.getX(), value.getY(), value.getZ());
    }

    @Override
    public Location deserialize(final String stored) {
        final String[] parts = stored.split(",");

        return new Location(Bukkit.getWorld(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
    }
}
```

JSONB is one column and tolerates shape changes, but its inner fields cannot be filtered or indexed cheaply. A value you would ever put in a `WHERE` clause belongs in real columns instead.

---

## Schema Management

The schema is derived from the registered properties — each carries its own `DataType`, including any converter's storage type. All four operations live on the repository and the first three run automatically on connect.

| Method | Behaviour |
|---|---|
| `createTable` | `CREATE TABLE IF NOT EXISTS` with a column per property and the identifier as primary key |
| `migrateSchema` | `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` for every property, additive only |
| `createIndexes` | Creates each index declared by `getIndexes()` if it does not exist |
| `dropTable` | `DROP TABLE IF EXISTS` |

Migration only adds columns. A column whose type has changed is left alone, so type changes have to be applied by hand before the table holds rows.

### Index Types

```java
@Override
protected Map<EntityProperty<Account, ?>, IndexType> getIndexes() {
    return Map.of(
            AccountProperty.EMAIL, IndexType.BTREE,
            AccountProperty.USERNAME, IndexType.BTREE,
            AccountProperty.DISPLAY_NAME, IndexType.GIN_TRGM
    );
}
```

| Type | Index | Use |
|---|---|---|
| `BTREE` | B-tree | Equality, ranges and ordering — the right choice for almost every column |
| `GIN_TRGM` | GIN with trigram operators | Substring and similarity search, the kind a `LIKE '%term%'` performs |
| `BRIN` | Block range | Range scans on a large append-only table whose values correlate with physical order; cannot serve an ordering |

Index names follow `idx_<table>_<column>`. The `pg_trgm` extension is installed by the driver before any index is created.

---

## Batch Queue

Every write goes through the `BatchQueue`. Nothing reaches the database at the moment a repository method is called.

Writes land in a map keyed by table and identifier, so a burst of edits to one entity collapses into a single statement. A scheduled thread drains that map on a fixed interval, sorting by arrival order, splitting into chunks, and committing each chunk in one transaction. Within a transaction, runs of identical SQL execute as a single JDBC batch.

```java
final BatchQueueSettings settings = new BatchQueueSettings();
settings.setFlushIntervalMillis(500L);
settings.setChunkSize(1_000);
```

| Setting | Default | Purpose |
|---|---|---|
| `chunkSize` | `500` | Maximum writes per transaction |
| `flushIntervalMillis` | `1000` | Delay between flushes, and the worst-case window of writes lost to a crash |
| `shutdownTimeoutSeconds` | `5` | How long shutdown waits for the flush thread |
| `writeWarnMillis` | `50` | Warn past this per group of same-shape statements |
| `commitWarnBaseMillis` | `150` | Fixed part of the commit warning threshold |
| `commitWarnPerWriteMillis` | `1` | Per-write part, added once for each write in the chunk |

The commit threshold scales with the write count deliberately — a commit costs a fixed fsync plus per-statement time, so a fixed ceiling would warn about a large chunk simply for being large.

| Behaviour | Detail |
|---|---|
| **Coalescing** | One pending entry per entity; a newer write merges into the pending one |
| **Ordering** | Arrival sequence stamped on first queue and preserved across merges |
| **Failure** | A chunk that throws is logged and skipped; later chunks still commit |
| **Shutdown** | Scheduler stopped, then one final drain; registered as a JVM shutdown hook and idempotent |

---

## Storage

`Storage<Key, Value, IndexValue>` is the shared contract for both cache tiers, so a storage can be swapped between local and distributed without touching the calling code. Subclasses supply `index` and `unIndex` to decide which key an entity is stored under, plus `getTTL`.

Three type parameters, because what a storage holds is separate from the entity it belongs to:

| Parameter | Meaning |
|---|---|
| `Key` | What entries are stored under — a `UUID`, an email, a username |
| `Value` | What is held under that key — the entity itself, or an identifier pointing at it |
| `IndexValue` | The entity the index rules operate on, always the entity |

A primary storage keys an entity on its identifier and holds the entity, so `Value` and `IndexValue` coincide. A secondary storage keys on an email or a username and holds only the identifier — `index` still needs the whole entity to derive both sides of the mapping, which is why the third parameter exists.

`reIndex(indexValue, previousKey)` is part of that contract. When the value a storage keys on changes — an account's email being updated — the entity must be moved, or it stays reachable under the stale key until the entry expires. The entity is passed in already holding its new value, with the old key supplied separately because it can no longer be derived.

`resolveKey` normalises keys on both sides of every operation, so a storage keyed on something case-insensitive overrides it once rather than relying on callers to pass a consistent form.

### LocalStorage

`ConcurrentHashMap`-backed, keyed by whatever the subclass indexes on.

```java
public class AccountIdLocalStorage extends LocalStorage<UUID, Account, Account> {

    @Override
    public Duration getTTL() {
        return Duration.ofMinutes(5);
    }

    @Override
    public void index(final Account account) {
        this.put(account.getId(), account);
    }

    @Override
    public void unIndex(final Account account) {
        this.remove(account.getId());
    }
}
```

Override `resolveKey` to normalise keys, so lookups match regardless of the caller's casing:

```java
@Override
public String resolveKey(final String key) {
    return key.toUpperCase(Locale.ROOT);
}
```

Entries expire by the storage's TTL, applied on write; reads do not extend it. There is no background scheduler — expired entries are dropped when read, and a full sweep runs every 100 operations.

A storage is unbounded unless `getMaxSize()` is overridden, which suits a working set with a natural ceiling — the players on a server. A storage a public endpoint can reach caches whatever gets requested, so it wants a bound:

```java
@Override
public int getMaxSize() {
    return 50_000;
}
```

A write is never refused. At capacity the storage sweeps expired entries first, and if that frees nothing it drops the entries closest to expiring — which, since one TTL covers the whole storage, is also the oldest-written ones. That is insertion order rather than least-recently-used: `ConcurrentHashMap` does not track access order, and the bookkeeping to add it would cost more than the eviction quality is worth. A TTL is the real control over what a storage holds; the cap is the backstop.

### RedisStorage

Lettuce-backed and shared across every instance pointing at the same Redis. Keys are prefixed with a namespace, and each namespace keeps its own Redis set of member keys — that index is what makes `keys()`, `values()` and `size()` possible without a `SCAN`.

```java
public class AccountIdRedisStorage extends RedisStorage<Account, Account> {

    public AccountIdRedisStorage(final MyRedisDriver redisDriver) {
        super(redisDriver, "account:id");
    }

    @Override
    public Duration getTTL() {
        return Duration.ofMinutes(30);
    }

    @Override
    public void index(final Account account) {
        this.put(account.getId().toString(), account);
    }

    @Override
    public void unIndex(final Account account) {
        this.remove(account.getId().toString());
    }

    @Override
    protected String serialize(final Account account) {
        return GSON.toJson(account);
    }

    @Override
    protected Account deserialize(final String value) {
        return GSON.fromJson(value, Account.class);
    }
}
```

**Key format:** `{namespace}:{key}` — e.g. `account:id:8f14e45f-...`, with the namespace index at `{namespace}:__index`.

Redis expires individual entries but cannot remove them from a set, so the index outlives some of the entries it names. `keys()`, `values()` and `size()` prune as they go; a `get` or `contains` miss leaves the index alone, since the vast majority of misses are keys that were never cached and pruning each one would double the cost of every cold lookup.

Those three methods read the whole namespace, in 512-key batches so no single command blocks Redis, and deserialise every value they find. They are proportional to the namespace size and belong in administrative paths, not on a per-request lookup.

A value that will not deserialise — whether it throws or decodes to `null` — is treated as a miss and evicted rather than returned, so a schema change poisons nothing and nothing is left behind to fail the same way on the next read. The entity is simply refetched from the database and cached again in its new shape.

`reIndex` matters more here than on the local tier — a stale Redis key serves the old entity to every server on the network, not just the one that wrote it.

### Reference Storages

A secondary key — an email, a username — maps to an entity's **identifier**, not to a second copy of the entity. `LocalEntityReferenceIdStorage` and `RedisEntityReferenceIdStorage` implement that mapping, leaving only `getKey` to write:

```java
public class AccountEmailLocalStorage extends LocalEntityReferenceIdStorage<String, Account> {

    @Override
    public Duration getTTL() {
        return Duration.ofMinutes(5);
    }

    @Override
    public String resolveKey(final String key) {
        return key.toUpperCase(Locale.ROOT);
    }

    @Override
    protected String getKey(final Account account) {
        return account.getEmail();
    }
}
```

```java
public class AccountEmailRedisStorage extends RedisEntityReferenceIdStorage<Account> {

    public AccountEmailRedisStorage(final MyRedisDriver redisDriver) {
        super(redisDriver, "account:email");
    }

    @Override
    public Duration getTTL() {
        return Duration.ofMinutes(30);
    }

    @Override
    public String resolveKey(final String key) {
        return key.toUpperCase(Locale.ROOT);
    }

    @Override
    protected String getKey(final Account account) {
        return account.getEmail();
    }
}
```

The Redis form serialises identifiers as their canonical string, so a malformed value can only come from something outside the class having written the key — and is evicted like any other value that will not decode.

Storing the entity in each storage instead would mean a copy per key to keep in step on every write, and copies that drift apart the moment one is refreshed and another is not. The cost of the indirection is a second hop, which is a map lookup locally and usually a cache hit on the identifier leg.

An entity with no key — an account with no email set — is skipped, since a null key is a no-op on both tiers.

### Local vs Redis

| | LocalStorage | RedisStorage |
|---|---|---|
| **Backing store** | `ConcurrentHashMap` | Redis via Lettuce |
| **TTL mechanism** | `CacheEntry` with a monotonic expiry | Native `SET ... PX` |
| **Key type** | Any object | `String` |
| **Serialisation** | None — stores Java objects directly | Subclass-supplied |
| **Scope** | Single JVM instance | Shared across all instances |
| **Eviction** | Lazy on read, a sweep every 100 operations, and an optional size cap | Handled by Redis, plus corrupt-value eviction on read |
| **Use case** | Hot data, same-instance caching | Distributed caching, cross-instance state |

---

## Lookup Provider

`LookupProvider` solves two problems at once: tier order, and the stampede.

A lookup tries local storage, then Redis, then the database, caching whatever it finds on the way back. While that is happening, the lookup is registered in an in-flight map — so a hundred callers asking for the same uncached entity produce one query, not a hundred. The first caller does the work and the rest wait on its result.

Lookups come in two shapes. An identifier lookup resolves straight to the entity. A lookup by anything else resolves to an identifier, and the caller feeds that into the identifier lookup — so one cached copy of the entity serves every path to it, and each leg coalesces on its own.

Every method exists in both forms, and both share the same in-flight registration:

| Method | Returns |
|---|---|
| `lookupEntitySynchronously` | `Optional<Entity>`, blocking |
| `lookupEntityAsynchronously` | `CompletableFuture<Optional<Entity>>` |
| `lookupIdSynchronously` | `Optional<UUID>`, blocking |
| `lookupIdAsynchronously` | `CompletableFuture<Optional<UUID>>` |
| `lookupAllValuesSynchronously` | `List<Entity>`, blocking |
| `lookupAllValuesAsynchronously` | `CompletableFuture<List<Entity>>` |

These are the plumbing. A manager calls `getEntityById*` and `getEntityByKey*` on its `EntityHolder` instead — those wrap the tier walks above and compose the two legs of a secondary lookup for you.

`singleAsynchronously`, `idAsynchronously` and `listAsynchronously` are exposed alongside them, so a manager can add a lookup of its own shape without reimplementing the coalescing.

Work runs on virtual threads, which suits blocking JDBC and Redis calls and means a synchronous caller waiting inside a lookup cannot starve a fixed pool.

Keys are namespaced so an identifier lookup and an email lookup never collide, and string keys are uppercased before being used as an in-flight key so callers differing only in casing still share one lookup. That uppercasing is for matching callers against each other and nothing else — each tier is handed the caller's key and applies its own `resolveKey` to decide what it actually reads.

An identifier resolved from the database is written back to both tiers by the lookup itself, since the mapping is fully described by the key and the identifier — there is no entity to hand to a caching consumer.

`lookupAllValues` takes both a predicate and an equivalent jOOQ condition — one is applied in memory to the locally cached entities, the other in SQL. Identifiers already found locally are excluded from the query, so the database only returns what the cache missed, and the merged result is deduplicated by identifier. The Redis tier is deliberately not consulted: scraping it means reading and deserialising the entire namespace on every call, and every entity it would have supplied comes back from the query anyway.

Coalescing keys on the rendered condition, so two callers filtering on different values do not share a lookup — which also means a filter over a high-cardinality column rarely coalesces at all.

---

## Driver Configuration

### PostgreSQL

`DatabaseDriver` is abstract so you can subclass it and annotate the subclass for your own framework, keeping the library free of any framework's annotations. The subclass builds its own `HikariConfig` from wherever your application keeps configuration.

```java
public class MyDatabaseDriver extends DatabaseDriver {

    public MyDatabaseDriver(final DatabaseConfig databaseConfig) {
        super(databaseConfig.toHikariConfig(), new BatchQueueSettings());
    }
}
```

The driver, every repository and every manager are components — the container builds the graph, and each repository registers itself with `DatabaseApi` as it is constructed.

```java
@Repository
public class AccountRepository extends EntityRepository<Account> {

    public AccountRepository(final MyDatabaseDriver databaseDriver) {
        super(databaseDriver, Account.class, "Accounts");
    }
}
```

`connect()` is called once the container has finished wiring — from a startup listener, an `@PostConstruct`, or your plugin's enable:

```java
@Singleton
@RequiredArgsConstructor
public class DatabaseInitializer {

    private final MyDatabaseDriver databaseDriver;
    private final MyRedisDriver redisDriver;

    @PostConstruct
    public void initialize() {
        this.redisDriver.connect();
        this.databaseDriver.connect();
    }

    @PreDestroy
    public void terminate() {
        this.databaseDriver.disconnect();
        this.redisDriver.disconnect();
    }
}
```

`connect()` opens the pool, builds the jOOQ context and batch queue, installs `pg_trgm`, then creates and migrates every registered repository's table and indexes. That ordering is why it runs after the container has constructed the repositories rather than as part of the driver's own construction.

`DatabaseApi` is where that registry lives — static, because a repository needs somewhere to register at construction time, when the driver may not exist yet. It also exposes a readiness flag:

```java
accountRepository.setLoaded(true);

if (DatabaseApi.isDatabaseLoaded()) {
    // every repository has finished loading
}
```

Nothing marks a repository loaded on its own — call `setLoaded(true)` once that entity's startup work is done, and gate on `isDatabaseLoaded()` before the application starts serving.

Two pgjdbc properties are applied automatically:

| Property | Reason |
|---|---|
| `stringtype=unspecified` | Lets Postgres coerce string binds into `jsonb` columns |
| `reWriteBatchedInserts=true` | Folds a batch of identical inserts into one multi-row statement |

`disconnect()` drains the batch queue before closing the pool, in that order — closing the pool first would lose every queued write.

### Redis

```java
@Singleton
public class MyRedisDriver extends RedisDriver {

    public MyRedisDriver(final RedisConfig redisConfig) {
        super(redisConfig.getAddress(), redisConfig.getPort(), redisConfig.getPassword(), 500L);
    }
}
```

Injected wherever Redis is needed — into a `RedisStorage`, or directly for pub/sub. Its `connect()` runs alongside the database driver's, before anything touches it.

```java
public class AccountIdRedisStorage extends RedisStorage<Account, Account> {

    public AccountIdRedisStorage(final MyRedisDriver redisDriver) {
        super(redisDriver, "account:id");
    }
}
```

Lettuce connections are thread-safe and multiplexed, so one connection serves every caller — there is no pool to size.

```java
// Direct command access
final String value = redisDriver.synchronous().get("key");
redisDriver.asynchronous().set("key", "value");

// Grouping several commands
redisDriver.useResource(commands -> {
    commands.set("key", "value");
    commands.expire("key", 60);
});

final String result = redisDriver.getResource(commands -> commands.get("key"));

// A single command as a future
final CompletableFuture<String> future = redisDriver.getAsyncResource(commands -> commands.get("key"));
```

`getAsyncResource` returns the command's own future, so nothing waits on a thread. It completes on Lettuce's event loop, which means any non-trivial continuation belongs on `thenApplyAsync` with an executor of your choosing rather than `thenApply`.

A driver is connected once and not reused after `disconnect()` — the closed connections are kept rather than nulled, so work still in flight during shutdown fails with Lettuce's own closed-connection error instead of a null dereference.

The timeout is Lettuce's **command** timeout, not a connect timeout — every command waits at most that long before failing. Keep it short when commands run on a latency-sensitive thread.

### Pub/Sub

The Redis driver doubles as a message bus, which is how a multi-instance deployment keeps its local caches honest. Nothing in the library wires this up for you — the local tier is per-instance, so an entity written on one instance leaves every other instance holding its own copy until the TTL lapses.

The instance that writes an entity caches it locally, writes it to Redis, and publishes its identifier. Every other instance reads the fresh copy back out of Redis and replaces what it holds:

```java
// On write
accountManager.cacheEntity(account);
redisDriver.publish("account:refresh", account.getId().toString());

// On every instance
redisDriver.subscribe("account:refresh", message -> EXECUTOR.execute(() ->
        accountManager.getIdRedisStorage().get(message).ifPresent(accountManager.getIdLocalStorage()::index)));
```

Publishing the identifier rather than the entity avoids an ordering bug: two writes racing means the older payload can land last, leaving every instance holding a stale value with no way to notice. Reading it back means the loser of the race gets whatever Redis holds, which is the winner.

Subscribers run on Lettuce's event loop, so hand the work to an executor rather than doing a Redis round trip and a decode on the I/O thread.

An entity whose secondary key changed needs the previous key too, or the old key keeps resolving on the instances that never saw the change — publish it alongside the identifier and `reIndex` on receipt.

Redis pub/sub is fire-and-forget: an instance disconnected during a publish never gets the message and holds its entry until the TTL lapses. Usually acceptable with short TTLs; if it isn't, that is the point to move to Streams with consumer groups.

The pub/sub connection is opened lazily on the first subscription and shared by every channel, with one `SUBSCRIBE` issued per channel however many subscribers register. A subscriber that throws is logged and does not stop the rest from being called.

---

## Architecture

```
Entity (Account)
    ↕ EntityProperty (column ↔ getter/setter/DataType, with optional ValueConverter)
    ↕ EntityRepository (schema, reads, write queueing)
    ↕ BatchQueue (coalescing, ordering, chunked transactions)
    ↕ DatabaseDriver (HikariCP pool + jOOQ context)
    ↕ DatabaseApi (repository registry, consulted on connect)

EntityHolder (AccountManager)
    ↕ LookupProvider (tier walk + request coalescing)
    ↕ LocalStorage (ConcurrentHashMap + CacheEntry TTL)
    ↕ RedisStorage (Lettuce + native TTL + namespace index)
    ↕ EntityRepository (database fallback)

Secondary key (email, username)
    ↕ LocalEntityReferenceIdStorage / RedisEntityReferenceIdStorage (key → identifier)
    ↕ EntityHolder (identifier → entity, one cached copy)
```

| Layer | Responsibility |
|---|---|
| **Entity** | Business object with a UUID identity and an identifier constructor |
| **EntityProperty** | Binds a column to its getter, setter and SQL type; registry of an entity's full column set |
| **ValueConverter** | Bidirectional conversion between a Java type and its storage type |
| **EntityRepository** | Schema management, reads, and write queueing for one entity type |
| **PendingWrite** | One entity's queued write, merged in place as further writes arrive |
| **BatchQueue** | Coalesces, orders, chunks and commits every deferred write |
| **DatabaseDriver** | Owns the pool, jOOQ context and batch queue; runs schema setup on connect |
| **DatabaseApi** | Static registry of every constructed repository, plus the readiness flag |
| **RedisDriver** | Shared Lettuce connection, command helpers and pub/sub |
| **Storage** | Unified key-value cache contract with TTL, key normalisation and `index`/`unIndex` |
| **LocalStorage** | In-process cache with lazy eviction, a periodic sweep and an optional size cap |
| **RedisStorage** | Distributed cache with a per-namespace key index and chunked whole-namespace reads |
| **LocalEntityReferenceIdStorage** | Local secondary key → identifier mapping, so the entity is cached once |
| **RedisEntityReferenceIdStorage** | The same mapping shared across every instance |
| **CacheEntry** | Cached value paired with a monotonic expiry |
| **LookupProvider** | Tiered lookup with stampede protection, sync and async |
| **EntityHolder** | Interface wiring a manager's repository, storages and lookup provider together |
