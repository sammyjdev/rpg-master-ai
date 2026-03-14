package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.EmbeddingPort;

/**
 * Embedding adapter backed by Spring AI's {@link EmbeddingModel}.
 * In the {@code local} profile this calls Ollama ({@code bge-m3}, 1024 dims).
 * In the {@code prod} profile swap this bean for a Bedrock/OpenAI adapter.
 *
 * <p>Always prefer {@link #embedBatch(List)} over calling {@link #embed(String)}
 * in a loop — the batch request is ~10x faster for large chunk sets.
 */
@Component
public class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiEmbeddingAdapter.class);
    private static final int EMBEDDING_BATCH_SIZE = 16;
    private static final int MAX_EMBED_TEXT_CHARS = 2000;

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** {@inheritDoc} */
    @Override
    public List<Float> embed(String text) {
        var sanitized = sanitizeForEmbedding(text);
        log.debug("Embedding query text ({} chars)", sanitized.length());
        var response = embeddingModel.embedForResponse(List.of(sanitized));
        float[] raw = response.getResults().getFirst().getOutput();
        return toFloatList(raw);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Spring AI's batch embed sends all texts in a single HTTP request to Ollama,
     * significantly reducing latency for bulk ingestion.
     */
    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        log.debug("Batch embedding {} texts", texts.size());
        if (texts.isEmpty()) {
            return List.of();
        }

        List<List<Float>> allEmbeddings = new java.util.ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(index + EMBEDDING_BATCH_SIZE, texts.size());
            var chunk = texts.subList(index, end).stream()
                    .map(this::sanitizeForEmbedding)
                    .toList();
            var response = embeddingModel.embedForResponse(chunk);
            response.getResults().stream()
                    .map(result -> toFloatList(result.getOutput()))
                    .forEach(allEmbeddings::add);
        }
        return allEmbeddings;
    }

    private String sanitizeForEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        var normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_EMBED_TEXT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_EMBED_TEXT_CHARS);
    }

    private List<Float> toFloatList(float[] raw) {
        List<Float> result = new java.util.ArrayList<>(raw.length);
        for (float v : raw) result.add(v);
        return result;
    }
}
