package io.github.trae.database.types.mongo.records;

import com.mongodb.client.model.WriteModel;
import org.bson.Document;

/**
 * Represents a single MongoDB write operation queued for batched execution.
 *
 * <p>Encapsulates the target database, collection, and the {@link WriteModel}
 * to be included in a {@code bulkWrite} call. Operations are grouped by
 * {@code databaseName.collectionName} at flush time.</p>
 *
 * @param databaseName   the target database name
 * @param collectionName the target collection name
 * @param writeModel     the MongoDB write model (insert, update, or delete)
 */
public record MongoWriteOperation(String databaseName, String collectionName, WriteModel<Document> writeModel) {
}