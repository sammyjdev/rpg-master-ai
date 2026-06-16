package com.rpgmaster.app.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j beans for the alternative retrieval path (ADR-014).
 * Reuses the SAME Ollama instance + bge-m3 model + Qdrant collection as the
 * primary Spring AI path — config is read from existing keys, not duplicated.
 */
@Configuration
public class LangChain4jConfig {

    @Bean("langchain4jEmbeddingModel")
    public EmbeddingModel langchain4jEmbeddingModel(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.embedding.model}") String model) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean("langchain4jQdrantStore")
    public EmbeddingStore<TextSegment> langchain4jQdrantStore(QdrantProperties props) {
        return QdrantEmbeddingStore.builder()
                .host(props.host())
                .port(props.port())
                .collectionName(props.collection())
                .payloadTextKey("text")
                .useTls(false)
                .build();
    }
}
