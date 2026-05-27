package com.rpgmaster.domain;

/**
 * Represents a user's natural language question sent to the RAG pipeline.
 *
 * <p>Production callers should obtain {@code topK} and {@code similarityThreshold}
 * from {@code RetrievalProperties} so all inbound adapters share the same defaults.
 * The 2-arg convenience constructor exists for tests and ad-hoc scripts only.
 *
 * @param question            The natural language question
 * @param rulebookId          Limits search to a specific rulebook; null = search all
 * @param topK                Number of chunks to retrieve from Qdrant (convenience default: 8)
 * @param similarityThreshold Minimum cosine similarity score to include a chunk (convenience default: 0.3)
 */
public record QueryRequest(
        String question,
        String rulebookId,
        int topK,
        float similarityThreshold
) {
    public QueryRequest(String question, String rulebookId) {
        this(question, rulebookId, 8, 0.3f);
    }
}
