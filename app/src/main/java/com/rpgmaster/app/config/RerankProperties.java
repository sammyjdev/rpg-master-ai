package com.rpgmaster.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reranking config. When {@code enabled}, the retrieval path fetches {@code topN}
 * candidates from the vector store and reorders them with a cross-encoder (TEI,
 * {@code model}) at {@code baseUrl}, keeping the caller's top-K.
 *
 * @param enabled whether reranking runs (off reproduces the vector-only baseline)
 * @param topN    candidates fetched before reranking (should be >= retrieval top-k)
 * @param model   reranker model id served by TEI; documentation-only — the app never
 *                reads this value, the actual model is selected by the TEI container's
 *                {@code --model-id} launch argument, so keep the two in sync manually
 * @param baseUrl TEI base URL (no trailing path)
 */
@ConfigurationProperties(prefix = "rpg.rerank")
public record RerankProperties(boolean enabled, int topN, String model, String baseUrl) {

    public RerankProperties {
        if (topN <= 0) {
            throw new IllegalArgumentException("rpg.rerank.top-n must be > 0, was " + topN);
        }
    }
}
