package com.rpgmaster.app.application.port;

import java.time.Instant;
import java.util.List;

/**
 * Port for document lifecycle persistence.
 * Adapter: {@code PostgresDocumentAdapter}.
 *
 * <p>Expresses business intent — create, complete, fail — rather than raw CRUD.
 * The use case does not need to know about JPA entities, SQL, or status enums.
 */
public interface DocumentRepository {

    /**
     * Persists a new document record with {@code PROCESSING} status.
     *
     * @param documentId generated UUID for the document
     * @param filename   original PDF filename
     * @param rulebookId namespace slug (e.g. "dnd-5e-phb")
     * @param uploadedAt timestamp when ingestion started
     */
    void create(String documentId, String filename, String rulebookId, Instant uploadedAt);

    /**
     * Marks a document as successfully ingested.
     *
     * @param documentId  the document to update
     * @param totalChunks number of chunks stored in the vector database
     */
    void complete(String documentId, int totalChunks);

    /**
     * Marks a document as failed.
     *
     * @param documentId the document to update
     */
    void fail(String documentId);

    /**
     * Lists rulebook identifiers that have been ingested successfully.
     *
     * @return distinct rulebook identifiers available for querying
     */
    List<String> listRulebookIds();
}
