package com.rpgmaster.domain;

/**
 * Sealed result type for document ingestion operations.
 * Use exhaustive switch (no default branch) in all handling code.
 *
 * <pre>{@code
 * switch (result) {
 *     case IngestionResult.Success s  -> log.info("Stored {} chunks", s.chunksStored());
 *     case IngestionResult.Failed f   -> log.error("Failed: {}", f.reason());
 *     case IngestionResult.Partial p  -> log.warn("Partial: {}/{}", p.chunksStored(), p.chunksFailed());
 * }
 * }</pre>
 */
public sealed interface IngestionResult
        permits IngestionResult.Success, IngestionResult.Failed, IngestionResult.Partial {

    /**
     * All chunks were successfully embedded and stored.
     *
     * @param documentId   The document that was processed
     * @param chunksStored Total number of chunks stored in Qdrant
     */
    record Success(String documentId, int chunksStored) implements IngestionResult {}

    /**
     * Ingestion failed completely — no chunks were stored.
     *
     * @param documentId The document that failed
     * @param reason     Human-readable failure description
     * @param cause      The underlying exception
     */
    record Failed(String documentId, String reason, Throwable cause) implements IngestionResult {}

    /**
     * Ingestion completed with some failures — partial data in Qdrant.
     *
     * @param documentId  The document that was partially processed
     * @param chunksStored Number of chunks successfully stored
     * @param chunksFailed Number of chunks that failed to store
     */
    record Partial(String documentId, int chunksStored, int chunksFailed) implements IngestionResult {}
}
