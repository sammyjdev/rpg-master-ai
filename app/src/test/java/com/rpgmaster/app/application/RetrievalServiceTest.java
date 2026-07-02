package com.rpgmaster.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock RetrievalPort retrievalPort;
    @Mock RerankPort rerankPort;

    private static SourceChunk chunk(String id) {
        return new SourceChunk(id, id + "-text", 1, 0.5f, "dnd-5e-phb");
    }

    private RetrievalService service(boolean rerankEnabled) {
        var rerank = new RerankProperties(rerankEnabled, 30, "m", "http://tei");
        return new RetrievalService(retrievalPort, rerankPort, rerank);
    }

    @Test
    @DisplayName("rerank disabled: searches at topK and does not call the reranker")
    void rerankDisabled() {
        when(retrievalPort.retrieve("rb", "q", 8, 0.3f)).thenReturn(List.of(chunk("a")));

        var result = service(false).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("a");
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("rerank enabled: searches at topN then reranks to topK")
    void rerankEnabled() {
        var candidates = List.of(chunk("a"), chunk("b"), chunk("c"));
        when(retrievalPort.retrieve("rb", "q", 30, 0.3f)).thenReturn(candidates);
        when(rerankPort.rerank("q", candidates, 8)).thenReturn(List.of(chunk("c"), chunk("a")));

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("c", "a");
        verify(retrievalPort).retrieve("rb", "q", 30, 0.3f);
    }

    @Test
    @DisplayName("rerank enabled but no candidates: returns empty, no rerank call")
    void rerankEnabledEmpty() {
        when(retrievalPort.retrieve(eq("rb"), eq("q"), eq(30), eq(0.3f))).thenReturn(List.of());

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).isEmpty();
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
