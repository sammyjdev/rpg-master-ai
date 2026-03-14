package com.rpgmaster.domain;

/**
 * A source chunk returned by the vector store, with its relevance score.
 * Used in QueryResult to show the user where the answer came from.
 *
 * @param chunkId    UUID of the chunk in Qdrant
 * @param text       The chunk text used as context
 * @param pageNumber Source page in the PDF
 * @param score      Cosine similarity score (0.0 to 1.0)
 * @param rulebookId Source rulebook — useful for cross-rulebook queries
 */
public record SourceChunk(
        String chunkId,
        String text,
        int pageNumber,
        float score,
        String rulebookId
) {}
