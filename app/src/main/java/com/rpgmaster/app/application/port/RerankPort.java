package com.rpgmaster.app.application.port;

import java.util.List;

import com.rpgmaster.domain.SourceChunk;

/**
 * Port for cross-encoder reranking. Adapter: {@link com.rpgmaster.app.adapter.outbound.TeiRerankAdapter}.
 */
public interface RerankPort {

    /**
     * Reorders {@code candidates} by cross-encoder relevance to {@code query} and
     * returns the top-{@code topK}. Returned chunks carry the reranker score in
     * {@link SourceChunk#score()}. Returns an empty list if {@code candidates} is empty.
     *
     * @param query      the user question
     * @param candidates ANN-retrieved chunks to reorder
     * @param topK       maximum number of chunks to return after reranking
     * @return reranked chunks, highest relevance first, at most {@code topK}
     */
    List<SourceChunk> rerank(String query, List<SourceChunk> candidates, int topK);
}
