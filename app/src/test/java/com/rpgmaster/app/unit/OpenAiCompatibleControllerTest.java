package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpgmaster.app.adapter.inbound.rest.OpenAiCompatibleController;
import com.rpgmaster.app.adapter.inbound.rest.OpenAiCompatibleController.OpenAiChatCompletionRequest;
import com.rpgmaster.app.adapter.inbound.rest.OpenAiCompatibleController.OpenAiChatCompletionResponse;
import com.rpgmaster.app.adapter.inbound.rest.OpenAiCompatibleController.OpenAiMessage;
import com.rpgmaster.app.application.QueryUseCase;
import com.rpgmaster.app.application.port.DocumentRepository;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.QueryResult;
import com.rpgmaster.domain.SourceChunk;

/**
 * Guards the OpenAI-compatible non-streaming response contract that GNOMON
 * depends on: {@code contexts} (source chunk texts) and
 * {@code usage.total_tokens} (from {@link QueryResult#tokensUsed()}).
 *
 * <p>Introduced in commit f435428; a rename or a {@code @JsonProperty} typo
 * would compile fine but silently break GNOMON, so this test asserts both the
 * record accessors and the actual serialized JSON keys.
 */
class OpenAiCompatibleControllerTest {

    @Test
    void chatCompletions_nonStreaming_returnsContextsAndTotalTokensFromQueryResult() {
        var queryUseCase = mock(QueryUseCase.class);
        var documentRepository = mock(DocumentRepository.class);
        var objectMapper = new ObjectMapper();
        var retrieval = new RetrievalProperties(8, 0.3f);
        var controller = new OpenAiCompatibleController(queryUseCase, documentRepository, objectMapper, retrieval);

        var sources = List.of(
                new SourceChunk("c1", "Fireball deals 8d6 fire damage.", 241, 0.9f, "dnd-5e-phb"),
                new SourceChunk("c2", "Fireball is a 3rd-level spell.", 242, 0.85f, "dnd-5e-phb"));
        when(queryUseCase.query(any())).thenReturn(new QueryResult("A 3rd-level fire spell.", sources, 123, 50L));

        var request = new OpenAiChatCompletionRequest(
                "dnd-5e-phb",
                List.of(new OpenAiMessage("user", "What is a Fireball?")),
                false);

        var responseEntity = controller.chatCompletions(request);
        var response = (OpenAiChatCompletionResponse) responseEntity.getBody();

        assertThat(response.contexts())
                .containsExactly("Fireball deals 8d6 fire damage.", "Fireball is a 3rd-level spell.");
        assertThat(response.usage().totalTokens()).isEqualTo(123);
    }

    @Test
    void chatCompletions_serializedJson_usesSnakeCaseKeysGnomonDependsOn() throws Exception {
        var queryUseCase = mock(QueryUseCase.class);
        var documentRepository = mock(DocumentRepository.class);
        var objectMapper = new ObjectMapper();
        var retrieval = new RetrievalProperties(8, 0.3f);
        var controller = new OpenAiCompatibleController(queryUseCase, documentRepository, objectMapper, retrieval);

        var sources = List.of(new SourceChunk("c1", "Fireball deals 8d6 fire damage.", 241, 0.9f, "dnd-5e-phb"));
        when(queryUseCase.query(any())).thenReturn(new QueryResult("answer", sources, 123, 50L));

        var request = new OpenAiChatCompletionRequest(
                "dnd-5e-phb",
                List.of(new OpenAiMessage("user", "What is a Fireball?")),
                false);

        var response = controller.chatCompletions(request).getBody();
        var json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"contexts\"");
        assertThat(json).contains("\"total_tokens\"");
        assertThat(json).doesNotContain("\"totalTokens\"");
    }
}
