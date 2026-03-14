package com.rpgmaster.app.application.port;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Port for streaming text generation from an LLM.
 * Increment 1 (CLI): callers use {@code .collectList().block()} to get the full response.
 * Increment 2+ (REST): callers return the {@code Flux<String>} directly for SSE streaming.
 *
 * Adapters: SpringAiLlmAdapter (Ollama local), BedrockLlmAdapter (prod).
 */
public interface LlmPort {

    /**
     * Generates a streaming response from the LLM using RAG context.
     *
     * @param systemPrompt the system instruction (persona + constraints)
     * @param userPrompt   the user's question
     * @param context      retrieved chunk texts to augment the prompt
     * @return token stream — subscribe to receive the generated answer incrementally
     */
    Flux<String> generateStream(String systemPrompt, String userPrompt, List<String> context);
}
