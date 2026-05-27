package com.rpgmaster.domain;

/**
 * Result of a single LLM generation call.
 *
 * @param text       the generated text
 * @param tokensUsed total tokens consumed (prompt + completion); {@code 0} when the provider
 *                   does not report usage metadata
 */
public record LlmResult(String text, int tokensUsed) {
}
