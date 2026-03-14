package com.rpgmaster.domain;

import java.util.List;

/**
 * The result of a RAG query — the generated answer plus supporting source chunks.
 *
 * @param answer     The LLM-generated answer, grounded in retrieved context
 * @param sources    The top-K chunks used as context, with scores
 * @param tokensUsed Approximate tokens consumed (input + output combined)
 * @param latencyMs  Total query latency in milliseconds (embed + search + generate)
 */
public record QueryResult(
        String answer,
        List<SourceChunk> sources,
        int tokensUsed,
        long latencyMs
) {}
