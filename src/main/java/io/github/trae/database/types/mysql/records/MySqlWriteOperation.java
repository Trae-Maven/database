package io.github.trae.database.types.mysql.records;

import java.util.List;

/**
 * Represents a single MySQL write operation queued for batched execution.
 *
 * <p>Encapsulates the target database, the parameterized SQL statement, and
 * the ordered list of bind values. Operations are grouped by
 * {@code databaseName} at flush time and executed within a single transaction.</p>
 *
 * @param databaseName the target database name
 * @param sql          the parameterized SQL statement
 * @param values       the ordered bind values for the prepared statement placeholders
 */
public record MySqlWriteOperation(String databaseName, String sql, List<Object> values) {
}