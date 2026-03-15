package com.rpgmaster.domain;

/**
 * Represents a user's natural language question sent to the RAG pipeline.
 *
 * @param question            The natural language question
 * @param rulebookId          Limits search to a specific rulebook; null = search all
 * @param topK                Number of chunks to retrieve from Qdrant (default: 5)
 * @param similarityThreshold Minimum cosine similarity score to include a chunk (default: 0.3)
 */
public record QueryRequest(
        String question,
        String rulebookId,
        int topK,
        float similarityThreshold
) {
    public QueryRequest(String question, String rulebookId) {
        this(question, rulebookId, 5, 0.3f);
    }
}
