# Database

A unified database abstraction layer providing annotation-driven domain mapping, repository-based CRUD operations, and multi-backend support for MongoDB and MySQL.

Database eliminates boilerplate by handling serialization, deserialization, batched writes, filtering, indexing, and async operations behind a single `DatabaseDriver` interface. Define a domain, annotate a repository, and the framework does the rest.

---

## Features

- **Domain mapping** — define persistable entities with a property enum and a `DomainData` constructor; the framework handles all serialization and deserialization
- **Repository pattern** — extend `AbstractRepository` for zero-boilerplate CRUD, sync/async reads, exists, count, and index management
- **Universal filter system** — fluent `FilterBuilder` with operators (equals, greater than, in, regex, exists, etc.) that translate to native queries on any backend
- **Query options** — sort, limit, and skip via `QueryOptions` for paginated and ordered queries
- **Index management** — declare single and compound indexes with `.on()` chaining and `.unique()`, applied identically across MongoDB and MySQL
- **Batched writes** — generic `BatchQueue<T>` with `ReentrantLock`-based thread safety, configurable batch size and flush interval, instant mode, and graceful shutdown with 30s termination timeout
- **MongoDB driver** — grouped `bulkWrite` per collection, `_id` as UUID primary key, full filter/sort/index translation to native BSON
- **MySQL driver** — HikariCP connection pooling, transactional batch execution, automatic `CREATE DATABASE`/`CREATE TABLE`, parameterized queries throughout

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
    ↕ AbstractRepository (mapping, delegation, index management)
    ↕ DatabaseDriver (backend-agnostic interface)
    ↕ MongoDatabaseDriver / MySqlDatabaseDriver (native driver calls)
    ↕ BatchQueue<T> (async batched execution)
```

| Layer | Responsibility |
|---|---|
| **Domain** | Business entity with UUID identity and property-based field access |
| **DomainProperty** | Enum defining the persistable fields on a domain |
| **DomainData** | Intermediate carrier wrapping raw database results for typed access |
| **AbstractRepository** | All CRUD, sync/async reads, exists, count, index management, domain mapping |
| **@Repository** | Annotation specifying database and collection names, meta-annotated with `@Component` |
| **DatabaseDriver** | Backend-agnostic interface for all database operations |
| **MongoDatabaseDriver** | MongoDB implementation with `bulkWrite` batching |
| **MySqlDatabaseDriver** | MySQL implementation with HikariCP and transactional batching |
| **BatchQueue** | Generic async batch queue with configurable flush strategy |
| **FilterBuilder** | Fluent API for building universal filter conditions |
| **QueryOptions** | Sort, limit, skip wrapper for paginated queries |
| **Index** | Universal index definition with `.on()` chaining |
