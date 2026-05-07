package com.rpgmaster.app.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.LlmPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.LlmResult;
import com.rpgmaster.domain.QueryRequest;
import com.rpgmaster.domain.QueryResult;
import com.rpgmaster.domain.SourceChunk;

import reactor.core.publisher.Flux;

/**
 * Orchestrates the synchronous RAG query pipeline:
 * <ol>
 *   <li>Embed the user's question</li>
 *   <li>Search Qdrant for top-K similar chunks (filtered by rulebookId)</li>
 *   <li>Build RAG prompt and call the LLM</li>
 *   <li>Return answer + source attribution</li>
 * </ol>
 *
 * <p>The {@link #queryStream} method returns a {@code Flux<String>} for Increment 2
 * SSE streaming. The CLI {@link #query} method blocks and collects the full answer.
 */
@Service
public class QueryUseCase {

    private static final Logger log = LoggerFactory.getLogger(QueryUseCase.class);

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final LlmPort llmPort;
        private final String ragSystemPrompt;

    public QueryUseCase(EmbeddingPort embeddingPort,
                        VectorStorePort vectorStorePort,
                                                LlmPort llmPort,
                                                @Qualifier("ragSystemPrompt") String ragSystemPrompt) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
        this.llmPort = llmPort;
                this.ragSystemPrompt = ragSystemPrompt;
    }

    /**
     * Executes a RAG query and returns the full answer (blocking).
     * Used by the Spring Shell CLI in Increment 1.
     *
     * @param request the query request with question and optional rulebookId
     * @return the complete query result with answer and source chunks
     */
    public QueryResult query(QueryRequest request) {
        var startMs = System.currentTimeMillis();

        log.info("Query: '{}', rulebook={}, topK={}", request.question(), request.rulebookId(), request.topK());

        // Step 1: Embed question
        var queryVector = embeddingPort.embed(request.question());

        // Step 2: Retrieve top-K chunks from Qdrant
        var sources = vectorStorePort.search(
                request.rulebookId(), queryVector, request.topK(), request.similarityThreshold()
        );
        log.info("Retrieved {} chunks from Qdrant", sources.size());

        if (sources.isEmpty()) {
            return new QueryResult("Not found in the rulebook.", List.of(), 0,
                    System.currentTimeMillis() - startMs);
        }

        // Step 3: Build context with page/rulebook metadata and call LLM (blocking for CLI)
        var contextTexts = buildContextWithMetadata(sources);
        LlmResult llmResult = llmPort.generateBlocking(ragSystemPrompt, request.question(), contextTexts);
        var latencyMs = System.currentTimeMillis() - startMs;

        log.info("Query complete in {}ms, tokensUsed={}", latencyMs, llmResult.tokensUsed());
        return new QueryResult(llmResult.text(), sources, llmResult.tokensUsed(), latencyMs);
    }

    /**
     * Executes a RAG query and returns a token stream (non-blocking).
     * Used by REST SSE endpoint in Increment 2+.
     *
     * @param request the query request
     * @return flux of LLM response tokens
     */
    public Flux<String> queryStream(QueryRequest request) {
        var queryVector = embeddingPort.embed(request.question());
        var sources = vectorStorePort.search(
                request.rulebookId(), queryVector, request.topK(), request.similarityThreshold()
        );
        if (sources.isEmpty()) {
            return Flux.just("Not found in the rulebook.");
        }
        var contextTexts = buildContextWithMetadata(sources);
        return llmPort.generateStream(ragSystemPrompt, request.question(), contextTexts);
    }

    private List<String> buildContextWithMetadata(List<SourceChunk> sources) {
        return sources.stream()
                .map(source -> "[Source: %s | Page: %d]\n%s".formatted(
                        source.rulebookId(), source.pageNumber(), source.text()))
                .toList();
    }
}
