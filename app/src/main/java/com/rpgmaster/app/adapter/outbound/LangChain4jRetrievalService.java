package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.domain.SourceChunk;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;

/**
 * Alternative retrieval path backed by LangChain4j (see ADR-012).
 * STUB — real embed + Qdrant search logic is added in Task 6 (TDD green phase).
 * Spring AI remains primary; this is for comparison only and is never wired
 * into {@code QueryUseCase}.
 */
@Component
public class LangChain4jRetrievalService implements RetrievalPort {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public LangChain4jRetrievalService(
            @Qualifier("langchain4jEmbeddingModel") EmbeddingModel embeddingModel,
            @Qualifier("langchain4jQdrantStore") EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /** {@inheritDoc} */
    @Override
    public List<SourceChunk> retrieve(String rulebookId, String queryText, int topK, float threshold) {
        // TODO(Task 6): embed via LangChain4j Ollama + search the Qdrant store.
        return List.of();
    }
}
