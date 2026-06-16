package com.rpgmaster.domain;

/**
 * The result of a blocking LLM generation — the answer text plus token usage.
 *
 * <p>Returned by {@code LlmPort.generateBlocking} for the CLI path (Increment 1).
 * The streaming path returns a {@code Flux<String>} instead and does not produce
 * this type.
 *
 * @param text       The generated answer text
 * @param tokensUsed Total tokens consumed (input + output combined), or 0 when
 *                   the provider did not report usage metadata
 */
public record LlmResult(
        String text,
        int tokensUsed
) {}
