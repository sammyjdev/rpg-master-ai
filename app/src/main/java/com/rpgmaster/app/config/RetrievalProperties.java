package com.rpgmaster.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralised retrieval defaults. Single source of truth for {@code topK} and
 * {@code similarityThreshold} across REST, CLI and any future inbound adapter.
 *
 * <p>Resolves gap-analysis A.3 §1 — previously the REST controller hardcoded
 * {@code topK=8} while the domain record defaulted to {@code 5}, so different
 * callers produced different results for the same question.
 *
 * @param topK                 number of chunks to retrieve from the vector store
 * @param similarityThreshold  minimum cosine similarity for a chunk to be returned
 */
@ConfigurationProperties(prefix = "rpg.retrieval")
public record RetrievalProperties(int topK, float similarityThreshold) {

    public RetrievalProperties {
        if (topK <= 0) {
            throw new IllegalArgumentException("rpg.retrieval.top-k must be > 0, was " + topK);
        }
        if (similarityThreshold < 0.0f || similarityThreshold > 1.0f) {
            throw new IllegalArgumentException(
                    "rpg.retrieval.similarity-threshold must be in [0.0, 1.0], was " + similarityThreshold);
        }
    }
}
