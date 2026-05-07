package com.rpgmaster.app.application.port;

import com.rpgmaster.domain.LlmResult;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Port for text generation from an LLM.
 * Increment 1 (CLI): use {@link #generateBlocking} to get the full response with token usage.
 * Increment 2+ (REST): use {@link #generateStream} and return the {@code Flux<String>} for SSE.
 *
 * Adapters: SpringAiLlmAdapter (Ollama local), BedrockLlmAdapter (prod).
 */
public interface LlmPort {

    /**
     * Generates a complete response from the LLM (blocking).
     * Returns the answer text and total tokens used.
     * Used by the CLI path in Increment 1.
     *
     * @param systemPrompt the system instruction (persona + constraints)
     * @param userPrompt   the user's question
     * @param context      retrieved chunk texts to augment the prompt
     * @return {@link LlmResult} with answer text and token count
     */
    LlmResult generateBlocking(String systemPrompt, String userPrompt, List<String> context);

    /**
     * Generates a streaming response from the LLM using RAG context.
     * Used by the REST SSE endpoint in Increment 2+.
     *
     * @param systemPrompt the system instruction (persona + constraints)
     * @param userPrompt   the user's question
     * @param context      retrieved chunk texts to augment the prompt
     * @return token stream — subscribe to receive the generated answer incrementally
     */
    Flux<String> generateStream(String systemPrompt, String userPrompt, List<String> context);
}
