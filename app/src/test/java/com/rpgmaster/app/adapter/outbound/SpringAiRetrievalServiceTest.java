package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

class SpringAiRetrievalServiceTest {

    private final EmbeddingPort embeddingPort = mock(EmbeddingPort.class);
    private final VectorStorePort vectorStorePort = mock(VectorStorePort.class);
    private final SpringAiRetrievalService service =
            new SpringAiRetrievalService(embeddingPort, vectorStorePort);

    @Test
    @DisplayName("retrieve embeds the query text then searches the vector store with that vector")
    void retrieveEmbedsThenSearches() {
        var queryVector = List.of(0.1f, 0.2f, 0.3f);
        var expected = List.of(new SourceChunk("c1", "Fireball deals 8d6.", 1, 0.92f, "dnd-5e-phb"));
        when(embeddingPort.embed("What is a fireball?")).thenReturn(queryVector);
        when(vectorStorePort.search("dnd-5e-phb", queryVector, 5, 0.3f)).thenReturn(expected);

        var result = service.retrieve("dnd-5e-phb", "What is a fireball?", 5, 0.3f);

        assertThat(result).isEqualTo(expected);
        verify(embeddingPort).embed("What is a fireball?");
        verify(vectorStorePort).search(eq("dnd-5e-phb"), eq(queryVector), eq(5), eq(0.3f));
    }
}
