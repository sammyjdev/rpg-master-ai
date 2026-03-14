package com.rpgmaster.app.adapter.inbound.rest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rpgmaster.app.application.QueryUseCase;
import com.rpgmaster.app.application.port.DocumentRepository;
import com.rpgmaster.domain.QueryRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;

@Tag(name = "Chat", description = "OpenAI-compatible chat completions and model listing")
@RestController
@RequestMapping("/v1")
public class OpenAiCompatibleController {

    private static final String ALL_RULEBOOKS_MODEL = "all-rulebooks";
    private static final int DEFAULT_TOP_K = 8;
    private static final float DEFAULT_THRESHOLD = 0.3f;

    private final QueryUseCase queryUseCase;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleController(QueryUseCase queryUseCase,
                                      DocumentRepository documentRepository,
                                      ObjectMapper objectMapper) {
        this.queryUseCase = queryUseCase;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "List available models",
               description = "Returns one model per ingested rulebook plus an 'all-rulebooks' model for cross-rulebook search.")
    @ApiResponse(responseCode = "200", description = "Model list")
    @GetMapping("/models")
    public OpenAiModelsResponse models() {
        var created = Instant.now().getEpochSecond();
        var models = new java.util.ArrayList<OpenAiModel>();
        models.add(new OpenAiModel(ALL_RULEBOOKS_MODEL, "model", created, "rpg-master"));
        documentRepository.listRulebookIds().stream()
            .map(rulebookId -> new OpenAiModel(rulebookId, "model", created, "rpg-master"))
            .forEach(models::add);
        return new OpenAiModelsResponse("list", models);
    }

    @Operation(summary = "Create chat completion",
               description = "Sends a question to the RAG pipeline. Set stream=true for SSE token streaming. "
                       + "The 'model' field selects the rulebook to search (use 'all-rulebooks' for cross-rulebook).")
    @ApiResponse(responseCode = "200", description = "Chat completion or SSE stream",
                 content = @Content(schema = @Schema(implementation = OpenAiChatCompletionResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (missing model or messages)")
    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chatCompletions(@RequestBody OpenAiChatCompletionRequest request) {
        validateRequest(request);
        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(streamingResponse(request));
        }

        var queryResult = queryUseCase.query(toQueryRequest(request));
        var response = new OpenAiChatCompletionResponse(
                completionId(),
                "chat.completion",
                Instant.now().getEpochSecond(),
                request.model(),
                List.of(new OpenAiChatChoice(
                        0,
                        new OpenAiAssistantMessage("assistant", queryResult.answer()),
                        "stop"
                ))
        );
        return ResponseEntity.ok(response);
    }

    private Flux<ServerSentEvent<String>> streamingResponse(OpenAiChatCompletionRequest request) {
        var completionId = completionId();
        var created = Instant.now().getEpochSecond();
        var queryRequest = toQueryRequest(request);

        var firstChunk = chunkJson(new OpenAiChatCompletionChunk(
                completionId,
                "chat.completion.chunk",
                created,
                request.model(),
                List.of(new OpenAiChunkChoice(0, new OpenAiDelta("assistant", ""), null))
        ));

        var tokenChunks = queryUseCase.queryStream(queryRequest)
                .map(token -> chunkJson(new OpenAiChatCompletionChunk(
                        completionId,
                        "chat.completion.chunk",
                        created,
                        request.model(),
                        List.of(new OpenAiChunkChoice(0, new OpenAiDelta(null, token), null))
                )));

        var lastChunk = chunkJson(new OpenAiChatCompletionChunk(
                completionId,
                "chat.completion.chunk",
                created,
                request.model(),
                List.of(new OpenAiChunkChoice(0, new OpenAiDelta(null, null), "stop"))
        ));

        return Flux.concat(
            Flux.just(toSseEvent(firstChunk)),
            tokenChunks.map(this::toSseEvent),
            Flux.just(toSseEvent(lastChunk), toSseEvent("[DONE]"))
        );
    }

    private QueryRequest toQueryRequest(OpenAiChatCompletionRequest request) {
        var userMessage = request.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalArgumentException("A user message is required."));

        var rulebookId = ALL_RULEBOOKS_MODEL.equals(request.model()) ? null : request.model();
        return new QueryRequest(userMessage.content(), rulebookId, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    private void validateRequest(OpenAiChatCompletionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (request.model() == null || request.model().isBlank()) {
            throw new IllegalArgumentException("Model is required.");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("At least one message is required.");
        }
    }

    private String completionId() {
        return "chatcmpl-" + UUID.randomUUID();
    }

    private String chunkJson(OpenAiChatCompletionChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize streaming response.", exception);
        }
    }

    private ServerSentEvent<String> toSseEvent(String payload) {
        return ServerSentEvent.<String>builder()
                .data(payload)
                .build();
    }

    @Schema(description = "OpenAI-compatible chat completion request")
    public record OpenAiChatCompletionRequest(
            @Schema(description = "Rulebook ID to search, or 'all-rulebooks'", example = "dnd-5e-phb")
            String model,
            @Schema(description = "Conversation messages — last 'user' message is the question")
            List<OpenAiMessage> messages,
            @Schema(description = "If true, response is streamed as SSE events", example = "false")
            Boolean stream) {
    }

    @Schema(description = "A chat message with role and content")
    public record OpenAiMessage(
            @Schema(description = "Message role", example = "user", allowableValues = {"system", "user", "assistant"})
            String role,
            @Schema(description = "Message text", example = "What is the Fireball spell?")
            String content) {
    }

    public record OpenAiModelsResponse(String object, List<OpenAiModel> data) {
    }

    public record OpenAiModel(String id,
                              String object,
                              long created,
                              @JsonProperty("owned_by") String ownedBy) {
    }

    public record OpenAiChatCompletionResponse(String id,
                                               String object,
                                               long created,
                                               String model,
                                               List<OpenAiChatChoice> choices) {
    }

    public record OpenAiChatChoice(int index,
                                   OpenAiAssistantMessage message,
                                   @JsonProperty("finish_reason") String finishReason) {
    }

    public record OpenAiAssistantMessage(String role, String content) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenAiChatCompletionChunk(String id,
                                            String object,
                                            long created,
                                            String model,
                                            List<OpenAiChunkChoice> choices) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenAiChunkChoice(int index,
                                    OpenAiDelta delta,
                                    @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenAiDelta(String role, String content) {
    }
}