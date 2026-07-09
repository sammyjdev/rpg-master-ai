package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

/**
 * Primary retrieval path. Composes the existing {@link EmbeddingPort} (Spring AI
 * Ollama bge-m3) and {@link VectorStorePort} (Qdrant gRPC) — the production stack.
 * This adapter adds no new infrastructure; it only fronts the two existing ports
 * behind {@link RetrievalPort} so it can be compared 1:1 against LangChain4j.
 */
@Component
@Primary
public class SpringAiRetrievalService implements RetrievalPort {

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;

    public SpringAiRetrievalService(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
    }

    /** {@inheritDoc} */
    @Override
    public List<SourceChunk> retrieve(String rulebookId, String queryText, int topK, float threshold) {
        var queryVector = embeddingPort.embed(queryText);
        return vectorStorePort.search(rulebookId, queryVector, topK, threshold);
    }
}
