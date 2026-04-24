package io.github.trae.database.repository.annotations;

import io.github.trae.di.annotations.type.component.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a database repository, specifying the target database and collection.
 *
 * <p>Meta-annotated with {@link Component}, so the dependency injector automatically
 * discovers and registers repository classes during classpath scanning — no additional
 * configuration required.</p>
 *
 * <p>The {@link #databaseName()} and {@link #collectionName()} values are read at
 * runtime by {@link io.github.trae.database.repository.interfaces.IAbstractRepository}
 * to route all CRUD operations to the correct database backend location.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * @Repository(databaseName = "Admin", collectionName = "Accounts")
 * public class AccountRepository extends AbstractRepository<Account, AccountProperty> {
 *     ...
 * }
 * }</pre>
 *
 * @see io.github.trae.database.repository.AbstractRepository
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Repository {

    /**
     * The name of the target database (or schema in MySQL).
     *
     * @return the database name
     */
    String databaseName();

    /**
     * The name of the target collection (MongoDB) or table (MySQL).
     *
     * @return the collection or table name
     */
    String collectionName();
}