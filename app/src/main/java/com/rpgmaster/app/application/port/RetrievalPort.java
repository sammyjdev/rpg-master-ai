package com.rpgmaster.app.application.port;

import com.rpgmaster.domain.SourceChunk;

import java.util.List;

/**
 * Port for end-to-end retrieval: embed a query string and return the most
 * similar chunks. Bundles the embed + vector-search steps so the entire
 * retrieval stack (embedding model + vector store) can be swapped as a unit.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code SpringAiRetrievalService} — primary; composes EmbeddingPort + VectorStorePort.</li>
 *   <li>{@code LangChain4jRetrievalService} — alternative under evaluation (see ADR-012).</li>
 * </ul>
 */
public interface RetrievalPort {

    /**
     * Embeds {@code queryText} and returns the most similar chunks.
     *
     * @param rulebookId namespace filter; {@code null} = search across all rulebooks
     * @param queryText  the natural-language query to embed and search with
     * @param topK       maximum number of results to return
     * @param threshold  minimum cosine similarity score (0.0–1.0)
     * @return scored chunks ordered by descending similarity
     */
    List<SourceChunk> retrieve(String rulebookId, String queryText, int topK, float threshold);
}
