package com.rpgmaster.app.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Structured record of a single RAG query, emitted to the {@code rpg.query.audit} logger
 * as one JSON line per query. Consumers can ship these to any log aggregator and replay
 * them as the basis for eval reports, prompt-version regression checks, or cost analysis.
 *
 * @param promptVersion       version of the system prompt that produced the answer
 * @param rulebookId          target rulebook (null = all rulebooks)
 * @param topK                effective topK used for retrieval
 * @param similarityThreshold effective similarity threshold used for retrieval
 * @param retrievalCount      how many chunks the vector store returned
 * @param promptTokens        input tokens consumed by the LLM (0 when unknown, e.g. streaming)
 * @param completionTokens    output tokens produced by the LLM (0 when unknown)
 * @param latencyMs           total wall-clock latency for the full RAG round-trip
 * @param mode                {@code "blocking"} or {@code "stream"} — distinguishes the two paths
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "prompt_version", "rulebook_id", "mode", "top_k", "similarity_threshold",
        "retrieval_count", "prompt_tokens", "completion_tokens", "latency_ms"
})
public record QueryAuditEvent(
        @JsonProperty("prompt_version") String promptVersion,
        @JsonProperty("rulebook_id") String rulebookId,
        @JsonProperty("mode") String mode,
        @JsonProperty("top_k") int topK,
        @JsonProperty("similarity_threshold") float similarityThreshold,
        @JsonProperty("retrieval_count") int retrievalCount,
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("latency_ms") long latencyMs
) {
}
