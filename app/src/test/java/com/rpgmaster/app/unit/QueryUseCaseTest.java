package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.rpgmaster.app.application.QueryUseCase;
import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.LlmPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.observability.QueryAuditEvent;
import com.rpgmaster.app.observability.QueryAuditLogger;
import com.rpgmaster.app.observability.RagMetrics;
import com.rpgmaster.domain.LlmResult;
import com.rpgmaster.domain.QueryRequest;
import com.rpgmaster.domain.SourceChunk;

import reactor.core.publisher.Flux;

/**
 * Unit coverage for the {@link QueryUseCase} orchestrator.
 *
 * <p>The matching integration test ({@code QueryIntegrationTest}) guards its
 * happy path behind {@code Assumptions.assumeTrue(isOllamaReachable())}, which
 * silently skips on any CI without Ollama. This test mocks every port so the
 * orchestration logic (embed → search → llm → audit + metrics) has real CI
 * signal regardless of which external services are reachable.
 */
class QueryUseCaseTest {

    private static final String PROMPT = "You are an RPG rules assistant.";
    private static final String PROMPT_VERSION = "v1.0.0";

    private EmbeddingPort embeddingPort;
    private VectorStorePort vectorStorePort;
    private LlmPort llmPort;
    private QueryAuditLogger auditLogger;
    private RagMetrics metrics;
    private QueryUseCase useCase;

    @BeforeEach
    void setUp() {
        embeddingPort = mock(EmbeddingPort.class);
        vectorStorePort = mock(VectorStorePort.class);
        llmPort = mock(LlmPort.class);
        auditLogger = mock(QueryAuditLogger.class);
        metrics = mock(RagMetrics.class);
        useCase = new QueryUseCase(
                embeddingPort, vectorStorePort, llmPort,
                PROMPT, PROMPT_VERSION,
                auditLogger, metrics);
    }

    private static SourceChunk chunk(String id, int page, String rulebookId) {
        return new SourceChunk(id, "Fireball deals 8d6 fire damage.", page, 0.91f, rulebookId);
    }

    @Test
    void blocking_happyPath_returnsAnswerAndEmitsAuditAndMetrics() {
        var vector = List.of(0.1f, 0.2f, 0.3f);
        var sources = List.of(
                chunk("c1", 241, "dnd-5e-phb"),
                chunk("c2", 242, "dnd-5e-phb"));
        when(embeddingPort.embed("What is a Fireball?")).thenReturn(vector);
        when(vectorStorePort.search("dnd-5e-phb", vector, 5, 0.5f)).thenReturn(sources);
        when(llmPort.generateBlocking(eq(PROMPT), eq("What is a Fireball?"), any()))
                .thenReturn(new LlmResult("A 3rd-level fire spell.", 250, 100));

        var result = useCase.query(new QueryRequest("What is a Fireball?", "dnd-5e-phb", 5, 0.5f));

        assertThat(result.answer()).isEqualTo("A 3rd-level fire spell.");
        assertThat(result.sources()).isEqualTo(sources);
        assertThat(result.tokensUsed()).isEqualTo(350);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);

        // Verify the LLM was called with context strings carrying [Source: …| Page: …] metadata.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> contextCaptor = ArgumentCaptor.forClass(List.class);
        verify(llmPort).generateBlocking(eq(PROMPT), eq("What is a Fireball?"), contextCaptor.capture());
        assertThat(contextCaptor.getValue())
                .hasSize(2)
                .allSatisfy(s -> assertThat(s).contains("[Source: dnd-5e-phb").contains("Page:"));

        var eventCaptor = ArgumentCaptor.forClass(QueryAuditEvent.class);
        verify(auditLogger, times(1)).log(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.promptVersion()).isEqualTo(PROMPT_VERSION);
        assertThat(event.mode()).isEqualTo("blocking");
        assertThat(event.rulebookId()).isEqualTo("dnd-5e-phb");
        assertThat(event.retrievalCount()).isEqualTo(2);
        assertThat(event.promptTokens()).isEqualTo(250);
        assertThat(event.completionTokens()).isEqualTo(100);
        assertThat(event.topK()).isEqualTo(5);
        assertThat(event.similarityThreshold()).isEqualTo(0.5f);

        verify(metrics).recordQuery(eq("dnd-5e-phb"), anyLong(), eq(250), eq(100));
    }

    @Test
    void blocking_refusalPath_whenVectorStoreEmpty_skipsLlmAndReturnsRefusal() {
        when(embeddingPort.embed(anyString())).thenReturn(List.of(0.1f, 0.2f));
        when(vectorStorePort.search(anyString(), any(), anyInt(), anyFloat()))
                .thenReturn(List.of());

        var result = useCase.query(new QueryRequest("Obscure question", "dnd-5e-phb", 5, 0.5f));

        assertThat(result.answer()).isEqualTo("Not found in the rulebook.");
        assertThat(result.sources()).isEmpty();
        assertThat(result.tokensUsed()).isZero();

        verify(llmPort, never()).generateBlocking(anyString(), anyString(), any());
        verify(llmPort, never()).generateStream(anyString(), anyString(), any());

        var eventCaptor = ArgumentCaptor.forClass(QueryAuditEvent.class);
        verify(auditLogger, times(1)).log(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.retrievalCount()).isZero();
        assertThat(event.promptTokens()).isZero();
        assertThat(event.completionTokens()).isZero();
        assertThat(event.mode()).isEqualTo("blocking");
        assertThat(event.promptVersion()).isEqualTo(PROMPT_VERSION);

        verify(metrics).recordQuery(eq("dnd-5e-phb"), anyLong(), eq(0), eq(0));
    }

    @Test
    void blocking_passesExplicitRulebookIdToVectorStore() {
        var vector = List.of(0.5f);
        when(embeddingPort.embed(anyString())).thenReturn(vector);
        when(vectorStorePort.search(eq("dnd-5e-dmg"), eq(vector), eq(3), eq(0.7f)))
                .thenReturn(List.of(chunk("c1", 10, "dnd-5e-dmg")));
        when(llmPort.generateBlocking(anyString(), anyString(), any()))
                .thenReturn(new LlmResult("answer", 10, 20));

        useCase.query(new QueryRequest("q", "dnd-5e-dmg", 3, 0.7f));

        verify(vectorStorePort).search(eq("dnd-5e-dmg"), eq(vector), eq(3), eq(0.7f));
    }

    @Test
    void blocking_passesNullRulebookIdForCrossRulebookSearch() {
        var vector = List.of(0.5f);
        when(embeddingPort.embed(anyString())).thenReturn(vector);
        when(vectorStorePort.search(isNull(), eq(vector), anyInt(), anyFloat()))
                .thenReturn(List.of(chunk("c1", 1, "dnd-5e-phb")));
        when(llmPort.generateBlocking(anyString(), anyString(), any()))
                .thenReturn(new LlmResult("answer", 5, 5));

        useCase.query(new QueryRequest("q", null, 8, 0.3f));

        verify(vectorStorePort).search(isNull(), eq(vector), eq(8), eq(0.3f));

        var eventCaptor = ArgumentCaptor.forClass(QueryAuditEvent.class);
        verify(auditLogger).log(eventCaptor.capture());
        assertThat(eventCaptor.getValue().rulebookId()).isNull();
    }

    @Test
    void stream_happyPath_emitsTokensAndFiresAuditOnTerminate() {
        var vector = List.of(0.1f, 0.2f);
        var sources = List.of(chunk("c1", 5, "dnd-5e-phb"));
        when(embeddingPort.embed(anyString())).thenReturn(vector);
        when(vectorStorePort.search(eq("dnd-5e-phb"), eq(vector), anyInt(), anyFloat()))
                .thenReturn(sources);
        when(llmPort.generateStream(eq(PROMPT), eq("stream q"), any()))
                .thenReturn(Flux.just("A ", "fireball ", "spell."));

        var tokens = useCase.queryStream(new QueryRequest("stream q", "dnd-5e-phb", 5, 0.5f))
                .collectList()
                .block();

        assertThat(tokens).containsExactly("A ", "fireball ", "spell.");

        var eventCaptor = ArgumentCaptor.forClass(QueryAuditEvent.class);
        verify(auditLogger, times(1)).log(eventCaptor.capture());
        var event = eventCaptor.getValue();
        assertThat(event.mode()).isEqualTo("stream");
        assertThat(event.retrievalCount()).isEqualTo(1);
        assertThat(event.promptVersion()).isEqualTo(PROMPT_VERSION);
        // Streaming path does not surface token counts (see LlmPort#generateStream contract).
        assertThat(event.promptTokens()).isZero();
        assertThat(event.completionTokens()).isZero();

        verify(metrics).recordQuery(eq("dnd-5e-phb"), anyLong(), eq(0), eq(0));
    }

    @Test
    void stream_refusalPath_whenVectorStoreEmpty_returnsRefusalFluxAndSkipsLlm() {
        when(embeddingPort.embed(anyString())).thenReturn(List.of(0.1f));
        when(vectorStorePort.search(anyString(), any(), anyInt(), anyFloat()))
                .thenReturn(List.of());

        var tokens = useCase.queryStream(new QueryRequest("q", "dnd-5e-phb", 5, 0.5f))
                .collectList()
                .block();

        assertThat(tokens).containsExactly("Not found in the rulebook.");
        verify(llmPort, never()).generateStream(anyString(), anyString(), any());

        var eventCaptor = ArgumentCaptor.forClass(QueryAuditEvent.class);
        verify(auditLogger, times(1)).log(eventCaptor.capture());
        assertThat(eventCaptor.getValue().mode()).isEqualTo("stream");
        assertThat(eventCaptor.getValue().retrievalCount()).isZero();
    }
}
