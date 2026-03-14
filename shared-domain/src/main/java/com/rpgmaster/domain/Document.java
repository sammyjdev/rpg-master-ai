package com.rpgmaster.domain;

import java.time.Instant;

/**
 * Represents a rulebook PDF document ingested into the system.
 * Status tracks the ingestion lifecycle.
 *
 * @param id          UUID
 * @param filename    Original filename of the uploaded PDF
 * @param rulebookId  Namespace slug — e.g. "dnd-5e-phb", "pathfinder-2e-core"
 * @param status      Ingestion lifecycle state
 * @param uploadedAt  Timestamp of upload (UTC)
 * @param totalChunks Total number of chunks produced (0 until COMPLETED)
 */
public record Document(
        String id,
        String filename,
        String rulebookId,
        IngestionStatus status,
        Instant uploadedAt,
        int totalChunks
) {
    /**
     * Lifecycle states for document ingestion.
     */
    public enum IngestionStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }
}
