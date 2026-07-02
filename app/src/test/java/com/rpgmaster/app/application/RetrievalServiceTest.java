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

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.SourceChunk;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock EmbeddingPort embeddingPort;
    @Mock VectorStorePort vectorStorePort;
    @Mock RerankPort rerankPort;

    private static SourceChunk chunk(String id) {
        return new SourceChunk(id, id + "-text", 1, 0.5f, "dnd-5e-phb");
    }

    private RetrievalService service(boolean rerankEnabled) {
        var retrieval = new RetrievalProperties(8, 0.3f);
        var rerank = new RerankProperties(rerankEnabled, 30, "m", "http://tei");
        return new RetrievalService(embeddingPort, vectorStorePort, rerankPort, retrieval, rerank);
    }

    @Test
    @DisplayName("rerank disabled: searches at topK and does not call the reranker")
    void rerankDisabled() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        when(vectorStorePort.search("rb", List.of(0.1f), 8, 0.3f)).thenReturn(List.of(chunk("a")));

        var result = service(false).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("a");
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("rerank enabled: searches at topN then reranks to topK")
    void rerankEnabled() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        var candidates = List.of(chunk("a"), chunk("b"), chunk("c"));
        when(vectorStorePort.search("rb", List.of(0.1f), 30, 0.3f)).thenReturn(candidates);
        when(rerankPort.rerank("q", candidates, 8)).thenReturn(List.of(chunk("c"), chunk("a")));

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("c", "a");
        verify(vectorStorePort).search("rb", List.of(0.1f), 30, 0.3f);
    }

    @Test
    @DisplayName("rerank enabled but no candidates: returns empty, no rerank call")
    void rerankEnabledEmpty() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        when(vectorStorePort.search(eq("rb"), any(), eq(30), eq(0.3f))).thenReturn(List.of());

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).isEmpty();
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
