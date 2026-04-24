package io.github.trae.database.types.mysql.records;

import java.util.List;

public record MySqlWriteOperation(String databaseName, String sql, List<Object> values) {
}