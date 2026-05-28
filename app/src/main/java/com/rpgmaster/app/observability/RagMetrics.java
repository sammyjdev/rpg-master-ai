package com.rpgmaster.app.observability;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.config.MetricsProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer facade for the contract declared in {@code CLAUDE.md → Observability Contracts}.
 *
 * <p>Each public method maps to exactly one metric name so dashboards can be
 * built against stable identifiers and so adding a new meter cannot
 * silently rename an existing one. Tags are deliberately low-cardinality
 * (no question text, no chunk IDs) to keep the Prometheus storage bill flat.
 */
@Component
public class RagMetrics {

    public static final String QUERY_LATENCY = "rpg.query.latency";
    public static final String QUERY_TOKENS = "rpg.query.tokens_used";
    public static final String QUERY_COST = "rpg.query.cost_usd";
    public static final String INGESTION_CHUNKS = "rpg.ingestion.chunks_processed";
    public static final String EMBEDDING_LATENCY = "rpg.embedding.latency";

    public static final String TAG_RULEBOOK = "rulebook_id";
    public static final String TAG_MODEL = "model";
    public static final String TAG_DIRECTION = "direction";
    public static final String TAG_STATUS = "status";
    public static final String TAG_BATCH_BUCKET = "batch_size";

    private final MeterRegistry registry;
    private final String chatModel;
    private final String embeddingModel;
    private final MetricsProperties pricing;

    public RagMetrics(MeterRegistry registry,
                      @Value("${spring.ai.ollama.chat.model:unknown}") String chatModel,
                      @Value("${spring.ai.ollama.embedding.model:unknown}") String embeddingModel,
                      MetricsProperties pricing) {
        this.registry = registry;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.pricing = pricing;
    }

    /**
     * Record a single completed RAG query. Called alongside {@link QueryAuditLogger}
     * so the JSON audit line and the Prometheus counters never drift apart.
     */
    public void recordQuery(String rulebookId, long latencyMs, int promptTokens, int completionTokens) {
        var rulebook = rulebookOrAll(rulebookId);

        Timer.builder(QUERY_LATENCY)
                .tag(TAG_RULEBOOK, rulebook)
                .tag(TAG_MODEL, chatModel)
                .register(registry)
                .record(Duration.ofMillis(latencyMs));

        incrementTokens(rulebook, "input", promptTokens);
        incrementTokens(rulebook, "output", completionTokens);

        var cost = (promptTokens * pricing.cost().promptUsdPer1k()
                + completionTokens * pricing.cost().completionUsdPer1k()) / 1000.0d;
        if (cost > 0) {
            Counter.builder(QUERY_COST)
                    .tag(TAG_MODEL, chatModel)
                    .register(registry)
                    .increment(cost);
        }
    }

    /**
     * Record an ingestion outcome. {@code status} should be one of
     * {@code success | failed | partial} so it stays bounded as a Prometheus label.
     */
    public void recordIngestion(String rulebookId, int chunksProcessed, String status) {
        Counter.builder(INGESTION_CHUNKS)
                .tag(TAG_RULEBOOK, rulebookOrAll(rulebookId))
                .tag(TAG_STATUS, status)
                .register(registry)
                .increment(chunksProcessed);
    }

    /**
     * Record a single embedding call. {@code batchSize} is bucketed
     * ({@code 1 | 2-10 | 11-100 | 100+}) to bound label cardinality.
     */
    public void recordEmbeddingLatency(long latencyMs, int batchSize) {
        Timer.builder(EMBEDDING_LATENCY)
                .tag(TAG_MODEL, embeddingModel)
                .tag(TAG_BATCH_BUCKET, bucket(batchSize))
                .register(registry)
                .record(Duration.ofMillis(latencyMs));
    }

    private void incrementTokens(String rulebook, String direction, int tokens) {
        if (tokens <= 0) {
            return;
        }
        Counter.builder(QUERY_TOKENS)
                .tag(TAG_RULEBOOK, rulebook)
                .tag(TAG_MODEL, chatModel)
                .tag(TAG_DIRECTION, direction)
                .register(registry)
                .increment(tokens);
    }

    private static String rulebookOrAll(String rulebookId) {
        return rulebookId == null || rulebookId.isBlank() ? "all" : rulebookId;
    }

    private static String bucket(int batchSize) {
        if (batchSize <= 1) return "1";
        if (batchSize <= 10) return "2-10";
        if (batchSize <= 100) return "11-100";
        return "100+";
    }
}
