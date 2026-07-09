package com.rpgmaster.app.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import com.rpgmaster.app.application.port.EmbeddingPort;

/**
 * Benchmark: sequential embed() vs batch embedBatch() for 80 distinct chunks.
 *
 * <p>Sequential: 80 individual Ollama round-trips per iteration.
 * Batch (embedBatch): ceil(80/16) = 5 Ollama calls per iteration.
 * Speedup = median_sequential / median_batch.
 *
 * <p>HONESTY NOTE: embedBatch is ~16x fewer round-trips, not a single HTTP request —
 * Spring AI batches 16 texts per Ollama call, so 80 texts = 5 calls, not 1.
 *
 * <p>Requires Ollama with bge-m3 on localhost:11434. Skips otherwise.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class EmbeddingBatchBenchmarkIT {

    private static final int DIM = 1024;
    private static final int WARMUP = 10;
    private static final int ITERATIONS = 15;

    // 80 distinct chunks: 8 base sentences × 10 suffixes
    private static final List<String> BASE_CORPUS = List.of(
            "A fireball is a 3rd-level evocation spell that deals 8d6 fire damage in a 20-foot radius.",
            "Counterspell is a 3rd-level abjuration spell that interrupts a creature casting a spell.",
            "A longsword is a martial melee weapon dealing 1d8 slashing damage, or 1d10 when wielded two-handed.",
            "The Barbarian's Rage grants advantage on Strength checks and resistance to physical damage.",
            "A Healing Potion restores 2d4 + 2 hit points when a creature drinks it as an action.",
            "Sneak Attack lets a Rogue deal extra damage when they have advantage on the attack roll.",
            "Darkvision allows a creature to see in dim light within 60 feet as if it were bright light.",
            "Armor Class represents how hard a creature is to hit; plate armor grants AC 18."
    );

    // Extend to 80 distinct chunks via 10 contextual suffixes per base sentence
    private static final List<String> CORPUS = buildCorpus();

    private static List<String> buildCorpus() {
        String[] suffixes = {
                " This rule applies in combat.",
                " Found in the Player's Handbook chapter 10.",
                " The dungeon master adjudicates edge cases.",
                " See table 3-2 for more details.",
                " This effect ends on a short rest.",
                " Concentration is required to maintain this effect.",
                " Multiple instances do not stack.",
                " This rule changed in errata version 2.0.",
                " It requires a spell slot of the appropriate level.",
                " Refer to the magic items appendix for exceptions."
        };
        List<String> corpus = new ArrayList<>(80);
        for (String base : BASE_CORPUS) {
            for (String suffix : suffixes) {
                corpus.add(base + suffix);
            }
        }
        return List.copyOf(corpus);
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("rpgmaster_test")
            .withUsername("rpgmaster")
            .withPassword("rpgmaster");

    @Container
    static QdrantContainer qdrant = new QdrantContainer("qdrant/qdrant:v1.12.5");

    @DynamicPropertySource
    @SuppressWarnings("unused")
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("qdrant.host", qdrant::getHost);
        registry.add("qdrant.port", () -> qdrant.getMappedPort(6334));
    }

    @Autowired
    private EmbeddingPort embeddingPort;

    @Test
    @DisplayName("Sequential embed() vs batch embedBatch() for 80 chunks (15 iterations each)")
    void embeddingBatchBenchmark() throws IOException {
        Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama (bge-m3) not reachable on localhost:11434.");

        assertThat(CORPUS).hasSize(80);

        // --- Warmup both paths ---
        for (int i = 0; i < WARMUP; i++) {
            embeddingPort.embed(CORPUS.getFirst());
        }
        for (int i = 0; i < WARMUP; i++) {
            embeddingPort.embedBatch(CORPUS.subList(0, 16)); // one batch call
        }

        // --- Sequential: 80 individual embed() calls per iteration ---
        long[] seqNanos = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            List<List<Float>> results = new ArrayList<>(CORPUS.size());
            for (String text : CORPUS) {
                results.add(embeddingPort.embed(text));
            }
            seqNanos[i] = System.nanoTime() - t0;
            // Sanity: 80 vectors of dim 1024
            assertThat(results).hasSize(80);
            assertThat(results.getFirst()).hasSize(DIM);
        }

        // --- Batch: embedBatch(all 80) = 5 Ollama calls (batches of 16) per iteration ---
        long[] batchNanos = new long[ITERATIONS];
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            List<List<Float>> results = embeddingPort.embedBatch(CORPUS);
            batchNanos[i] = System.nanoTime() - t0;
            // Sanity: 80 vectors of dim 1024
            assertThat(results).hasSize(80);
            assertThat(results.getFirst()).hasSize(DIM);
        }

        // Compute stats
        Stats seq   = computeStats(seqNanos);
        Stats batch = computeStats(batchNanos);
        double speedup = seq.median / batch.median;

        String report = buildReport(seq, batch, speedup);
        System.out.println(report);

        // Write to build/benchmark-embed.txt
        Path outDir = Path.of("build");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("benchmark-embed.txt"), report);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isOllamaReachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 11434), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Stats computeStats(long[] nanos) {
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        double mean   = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;
        double min    = sorted[0] / 1_000_000.0;
        double p95    = percentile(sorted, 95);
        double median = percentile(sorted, 50);
        return new Stats(min, median, p95, mean);
    }

    private static double percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx] / 1_000_000.0;
    }

    private static String buildReport(Stats seq, Stats batch, double speedup) {
        int batchCalls = (int) Math.ceil(80.0 / 16);
        return """
                ============================================================
                Embedding Benchmark: Sequential vs Batch (warmup=%d, iterations=%d)
                Corpus: %d distinct chunks (~100-300 chars each)
                Sequential: 80 embed() calls/iter (80 Ollama round-trips)
                Batch:      embedBatch(80) = %d Ollama calls/iter (EMBEDDING_BATCH_SIZE=16)
                HONESTY: batch is ~%dx fewer round-trips, not a single request.
                ============================================================
                Mode       | min(ms) | median(ms) | p95(ms) | mean(ms)
                -----------|---------|------------|---------|----------
                Sequential | %7.2f | %10.2f | %7.2f | %8.2f
                Batch      | %7.2f | %10.2f | %7.2f | %8.2f
                ------------------------------------------------------------
                Speedup (median_seq / median_batch): %.2fx
                NOTE: results are hardware/network-dependent; Ollama GPU vs CPU matters.
                ============================================================
                """.formatted(
                WARMUP, ITERATIONS,
                CORPUS.size(),
                batchCalls, batchCalls,
                seq.min, seq.median, seq.p95, seq.mean,
                batch.min, batch.median, batch.p95, batch.mean,
                speedup
        );
    }

    private record Stats(double min, double median, double p95, double mean) {}
}
