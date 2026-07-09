package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.domain.SourceChunk;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;

/**
 * Alternative retrieval path backed by LangChain4j (see ADR-014).
 * Spring AI remains primary; this is for comparison only and is never wired
 * into {@code QueryUseCase}.
 */
@Component
public class LangChain4jRetrievalService implements RetrievalPort {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jRetrievalService.class);
    private static final String RULEBOOK_ID_FIELD = "rulebook_id";

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
        Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        var requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore((double) threshold);

        if (rulebookId != null) {
            Filter filter = new IsEqualTo(RULEBOOK_ID_FIELD, rulebookId);
            requestBuilder.filter(filter);
        }

        var matches = embeddingStore.search(requestBuilder.build()).matches();
        log.debug("LangChain4j retrieved {} matches for rulebook={}", matches.size(), rulebookId);

        return matches.stream()
                .map(this::toSourceChunk)
                .toList();
    }

    private SourceChunk toSourceChunk(EmbeddingMatch<TextSegment> match) {
        var metadata = match.embedded().metadata();
        String text = match.embedded().text();
        Integer page = metadata.getInteger("page_number");
        String rb = metadata.getString(RULEBOOK_ID_FIELD);
        return new SourceChunk(
                match.embeddingId(),
                text,
                page != null ? page : 0,
                match.score().floatValue(),
                rb);
    }
}
