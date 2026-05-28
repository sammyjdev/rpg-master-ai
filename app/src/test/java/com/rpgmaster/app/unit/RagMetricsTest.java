package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rpgmaster.app.config.MetricsProperties;
import com.rpgmaster.app.observability.RagMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RagMetricsTest {

    private MeterRegistry registry;
    private RagMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        var pricing = new MetricsProperties(new MetricsProperties.Cost(0.0d, 0.0d));
        metrics = new RagMetrics(registry, "qwen2.5:7b", "bge-m3", pricing);
    }

    @Test
    void recordQueryEmitsLatencyTimerAndTokenCounters() {
        metrics.recordQuery("dnd-5e-phb", 1234L, 250, 100);

        var latency = registry.find(RagMetrics.QUERY_LATENCY)
                .tag(RagMetrics.TAG_RULEBOOK, "dnd-5e-phb")
                .tag(RagMetrics.TAG_MODEL, "qwen2.5:7b")
                .timer();
        assertThat(latency).isNotNull();
        assertThat(latency.count()).isEqualTo(1);

        var inputTokens = registry.find(RagMetrics.QUERY_TOKENS)
                .tag(RagMetrics.TAG_DIRECTION, "input")
                .counter();
        assertThat(inputTokens.count()).isEqualTo(250.0d);

        var outputTokens = registry.find(RagMetrics.QUERY_TOKENS)
                .tag(RagMetrics.TAG_DIRECTION, "output")
                .counter();
        assertThat(outputTokens.count()).isEqualTo(100.0d);
    }

    @Test
    void costCounterStaysAtZeroWhenPricingIsZero() {
        metrics.recordQuery("dnd-5e-phb", 1000L, 500, 200);

        // With zero pricing the counter is never registered (the meter only
        // increments when cost > 0), so finding it should yield null.
        var cost = registry.find(RagMetrics.QUERY_COST).counter();
        assertThat(cost).isNull();
    }

    @Test
    void costCounterIncrementsWhenPricingIsConfigured() {
        // $1 per 1k input + $2 per 1k output → (500*1 + 200*2) / 1000 = 0.90
        var pricing = new MetricsProperties(new MetricsProperties.Cost(1.0d, 2.0d));
        metrics = new RagMetrics(registry, "qwen2.5:7b", "bge-m3", pricing);

        metrics.recordQuery("dnd-5e-phb", 1000L, 500, 200);

        var cost = registry.find(RagMetrics.QUERY_COST)
                .tag(RagMetrics.TAG_MODEL, "qwen2.5:7b")
                .counter();
        assertThat(cost).isNotNull();
        assertThat(cost.count()).isEqualTo(0.9d);
    }

    @Test
    void nullRulebookIsTaggedAsAll() {
        metrics.recordQuery(null, 100L, 1, 1);

        var counter = registry.find(RagMetrics.QUERY_TOKENS)
                .tag(RagMetrics.TAG_RULEBOOK, "all")
                .tag(RagMetrics.TAG_DIRECTION, "input")
                .counter();
        assertThat(counter).isNotNull();
    }

    @Test
    void recordIngestionEmitsChunkCounterTaggedWithStatus() {
        metrics.recordIngestion("dnd-5e-phb", 856, "success");

        var counter = registry.find(RagMetrics.INGESTION_CHUNKS)
                .tag(RagMetrics.TAG_RULEBOOK, "dnd-5e-phb")
                .tag(RagMetrics.TAG_STATUS, "success")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(856.0d);
    }

    @Test
    void embeddingLatencyBucketsBatchSize() {
        metrics.recordEmbeddingLatency(50L, 1);
        metrics.recordEmbeddingLatency(60L, 5);
        metrics.recordEmbeddingLatency(70L, 50);
        metrics.recordEmbeddingLatency(80L, 500);

        assertThat(registry.find(RagMetrics.EMBEDDING_LATENCY).tag(RagMetrics.TAG_BATCH_BUCKET, "1").timer()).isNotNull();
        assertThat(registry.find(RagMetrics.EMBEDDING_LATENCY).tag(RagMetrics.TAG_BATCH_BUCKET, "2-10").timer()).isNotNull();
        assertThat(registry.find(RagMetrics.EMBEDDING_LATENCY).tag(RagMetrics.TAG_BATCH_BUCKET, "11-100").timer()).isNotNull();
        assertThat(registry.find(RagMetrics.EMBEDDING_LATENCY).tag(RagMetrics.TAG_BATCH_BUCKET, "100+").timer()).isNotNull();
    }
}
