package com.rpgmaster.app.application.port;

import java.util.List;

/**
 * Port for converting text into vector embeddings.
 * Adapters: SpringAiEmbeddingAdapter (Ollama local), BedrockEmbeddingAdapter (prod).
 */
public interface EmbeddingPort {

    /**
     * Embeds a single text string into a float vector.
     *
     * @param text the input text to embed (max ~512 tokens for bge-m3)
     * @return 1024-dimensional float vector (bge-m3) or 1536-dim (OpenAI ada-002)
     */
    List<Float> embed(String text);

    /**
     * Embeds multiple texts in a single batch request.
     * Significantly faster than calling {@link #embed(String)} in a loop.
     *
     * @param texts list of input texts to embed
     * @return list of float vectors, same order as input
     */
    List<List<Float>> embedBatch(List<String> texts);
}
