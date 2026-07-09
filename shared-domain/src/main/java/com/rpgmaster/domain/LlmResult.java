package com.rpgmaster.domain;

/**
 * Result of a single LLM generation call.
 *
 * @param text             the generated text
 * @param promptTokens     input tokens consumed (prompt); {@code 0} when the provider does not report usage
 * @param completionTokens output tokens generated; {@code 0} when the provider does not report usage
 */
public record LlmResult(String text, int promptTokens, int completionTokens) {

    /** Total tokens (prompt + completion). */
    public int tokensUsed() {
        return promptTokens + completionTokens;
    }
}
