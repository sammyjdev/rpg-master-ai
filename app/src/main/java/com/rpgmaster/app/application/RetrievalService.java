package com.rpgmaster.app.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.SourceChunk;

/**
 * The single retrieval path shared by the query pipeline and the eval harness.
 * Embeds the question, searches the vector store, and — when reranking is enabled
 * — fetches {@code topN} candidates and reorders them with the cross-encoder,
 * keeping the caller's {@code topK}. With reranking disabled it searches directly
 * at {@code topK}, reproducing the vector-only baseline.
 */
@Service
public class RetrievalService {

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final RerankPort rerankPort;
    private final RetrievalProperties retrieval;
    private final RerankProperties rerank;

    public RetrievalService(EmbeddingPort embeddingPort,
                            VectorStorePort vectorStorePort,
                            RerankPort rerankPort,
                            RetrievalProperties retrieval,
                            RerankProperties rerank) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
        this.rerankPort = rerankPort;
        this.retrieval = retrieval;
        this.rerank = rerank;
    }

    /**
     * Retrieves up to {@code topK} chunks for the question.
     *
     * @param rulebookId namespace filter; null = all rulebooks
     * @param question   the user question
     * @param threshold  minimum cosine similarity for the vector search
     * @param topK       chunks to return (query path: production top-k; eval: sweep maxK)
     */
    public List<SourceChunk> retrieve(String rulebookId, String question, float threshold, int topK) {
        var vector = embeddingPort.embed(question);
        if (!rerank.enabled()) {
            return vectorStorePort.search(rulebookId, vector, topK, threshold);
        }
        int topN = Math.max(rerank.topN(), topK);
        var candidates = vectorStorePort.search(rulebookId, vector, topN, threshold);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rerankPort.rerank(question, candidates, topK);
    }
}
