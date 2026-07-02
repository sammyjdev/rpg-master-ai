package com.rpgmaster.app.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

/**
 * The single retrieval path shared by the query pipeline and the eval harness.
 * Delegates the embed + vector-search step to {@link RetrievalPort} (the
 * swappable retrieval backend), and — when reranking is enabled — fetches
 * {@code topN} candidates and reorders them with the cross-encoder, keeping
 * the caller's {@code topK}. With reranking disabled it searches directly at
 * {@code topK}, reproducing the vector-only baseline.
 */
@Service
public class RetrievalService {

    private final RetrievalPort retrievalPort;
    private final RerankPort rerankPort;
    private final RerankProperties rerank;

    public RetrievalService(RetrievalPort retrievalPort,
                            RerankPort rerankPort,
                            RerankProperties rerank) {
        this.retrievalPort = retrievalPort;
        this.rerankPort = rerankPort;
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
        if (!rerank.enabled()) {
            return retrievalPort.retrieve(rulebookId, question, topK, threshold);
        }
        int topN = Math.max(rerank.topN(), topK);
        var candidates = retrievalPort.retrieve(rulebookId, question, topN, threshold);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rerankPort.rerank(question, candidates, topK);
    }
}
