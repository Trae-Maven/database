package io.github.trae.database.types.mongo.records;

import com.mongodb.client.model.WriteModel;
import org.bson.Document;

public record MongoWriteOperation(String databaseName, String collectionName, WriteModel<Document> writeModel) {
}