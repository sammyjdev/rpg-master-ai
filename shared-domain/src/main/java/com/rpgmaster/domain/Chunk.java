package com.rpgmaster.domain;

import java.util.Collections;
import java.util.Map;

/**
 * Represents a text chunk extracted from a rulebook PDF.
 * Immutable by design — all fields are final via Record semantics.
 *
 * @param id           UUID — primary key for Qdrant upsert (idempotent)
 * @param text         Raw extracted text content (max ~512 tokens)
 * @param tokenCount   Approximate token count (whitespace-split heuristic)
 * @param pageNumber   Source page in the PDF (1-indexed)
 * @param documentId   FK to the parent Document
 * @param rulebookId   Qdrant payload filter key — e.g. "dnd-5e-phb"
 * @param metadata     Arbitrary key-value metadata (e.g. chunkType, chapterTitle)
 */
public record Chunk(
        String id,
        String text,
        int tokenCount,
        int pageNumber,
        String documentId,
        String rulebookId,
        Map<String, String> metadata
) {
    public Chunk {
        // Defensive copy to preserve immutability
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(metadata);
    }
}
