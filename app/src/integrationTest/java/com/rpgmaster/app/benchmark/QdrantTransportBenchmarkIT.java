package com.rpgmaster.app.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

/**
 * Benchmark: gRPC (via VectorStorePort) vs HTTP REST (via WebClient) Qdrant search.
 *
 * <p>Seeds ~200 random 1024-dim vectors, then runs WARMUP=10 + ITERATIONS=50 per transport.
 * Results written to build/benchmark-transport.txt. No latency threshold assertions.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class QdrantTransportBenchmarkIT {

    private static final String RULEBOOK_ID = "benchmark";
    private static final int DIM = 1024;
    private static final int SEED_COUNT = 200;
    private static final int WARMUP = 10;
    private static final int ITERATIONS = 50;
    private static final int TOP_K = 10;

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
    private VectorStorePort vectorStorePort;

    private WebClient restClient;
    private List<Float> queryVector;

    @BeforeEach
    void seedAndBuildClient() {
        // Seed 200 random vectors under rulebookId "benchmark"
        var rng = new Random(42);
        var chunks = new ArrayList<VectorStorePort.ChunkVector>(SEED_COUNT);
        for (int i = 0; i < SEED_COUNT; i++) {
            chunks.add(new VectorStorePort.ChunkVector(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    RULEBOOK_ID,
                    "benchmark chunk " + i,
                    i + 1,
                    randomVector(rng, DIM)
            ));
        }
        vectorStorePort.upsert(chunks);

        // Build REST client targeting mapped port 6333
        int restPort = qdrant.getMappedPort(6333);
        String restHost = qdrant.getHost();
        restClient = WebClient.builder()
                .baseUrl("http://" + restHost + ":" + restPort)
                .build();

        // Random query vector
        queryVector = randomVector(new Random(99), DIM);
    }

    @Test
    @DisplayName("gRPC vs REST transport latency benchmark (50 iterations each)")
    void transportLatencyBenchmark() throws IOException {
        // --- gRPC via VectorStorePort ---
        long[] grpcNanos = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            vectorStorePort.search(RULEBOOK_ID, queryVector, TOP_K, 0.0f);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            List<SourceChunk> results = vectorStorePort.search(RULEBOOK_ID, queryVector, TOP_K, 0.0f);
            grpcNanos[i] = System.nanoTime() - t0;
            assertThat(results).isNotNull(); // sanity: must return (may be empty for random vectors)
        }

        // --- REST via WebClient ---
        // Build request body: vector array + search params
        String vectorJson = buildVectorJson(queryVector);
        String body = "{\"vector\":" + vectorJson + ",\"limit\":" + TOP_K
                + ",\"with_payload\":false,\"score_threshold\":0.0,"
                + "\"filter\":{\"must\":[{\"key\":\"rulebookId\",\"match\":{\"value\":\"" + RULEBOOK_ID + "\"}}]}}";

        long[] restNanos = new long[ITERATIONS];
        for (int i = 0; i < WARMUP; i++) {
            doRestSearch(body);
        }
        for (int i = 0; i < ITERATIONS; i++) {
            long t0 = System.nanoTime();
            String response = doRestSearch(body);
            restNanos[i] = System.nanoTime() - t0;
            assertThat(response).isNotNull();
        }

        // Compute stats
        Stats grpc = computeStats(grpcNanos);
        Stats rest = computeStats(restNanos);

        double ratio = rest.median / grpc.median;

        String report = buildReport(grpc, rest, ratio);
        System.out.println(report);

        // Write to build/benchmark-transport.txt
        Path outDir = Path.of("build");
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("benchmark-transport.txt"), report);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String doRestSearch(String body) {
        return restClient.post()
                .uri("/collections/rpg-chunks/points/search")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private static List<Float> randomVector(Random rng, int dim) {
        List<Float> v = new ArrayList<>(dim);
        float sum = 0f;
        for (int i = 0; i < dim; i++) {
            float val = rng.nextFloat() * 2f - 1f;
            v.add(val);
            sum += val * val;
        }
        // L2-normalize (cosine similarity needs unit vectors)
        float norm = (float) Math.sqrt(sum);
        if (norm > 0f) {
            for (int i = 0; i < dim; i++) v.set(i, v.get(i) / norm);
        }
        return v;
    }

    private static String buildVectorJson(List<Float> vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(vector.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private static Stats computeStats(long[] nanos) {
        long[] sorted = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;
        double min  = sorted[0] / 1_000_000.0;
        double max  = sorted[sorted.length - 1] / 1_000_000.0;
        double median = percentile(sorted, 50);
        double p95    = percentile(sorted, 95);
        return new Stats(min, median, p95, mean, max);
    }

    private static double percentile(long[] sorted, int pct) {
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        idx = Math.max(0, Math.min(idx, sorted.length - 1));
        return sorted[idx] / 1_000_000.0;
    }

    private static String buildReport(Stats grpc, Stats rest, double ratio) {
        return """
                ============================================================
                Qdrant Transport Benchmark (warmup=%d, iterations=%d, seed=%d, dim=%d)
                ============================================================
                Transport | min(ms) | median(ms) | p95(ms) | mean(ms) | max(ms)
                ----------|---------|------------|---------|----------|--------
                gRPC      | %7.2f | %10.2f | %7.2f | %8.2f | %7.2f
                REST      | %7.2f | %10.2f | %7.2f | %8.2f | %7.2f
                ------------------------------------------------------------
                REST_median / gRPC_median ratio: %.2fx
                (Higher ratio = REST is slower relative to gRPC)
                NOTE: results are hardware/JVM-dependent; JVM warmup affects early iterations.
                ============================================================
                """.formatted(
                WARMUP, ITERATIONS, SEED_COUNT, DIM,
                grpc.min, grpc.median, grpc.p95, grpc.mean, grpc.max,
                rest.min, rest.median, rest.p95, rest.mean, rest.max,
                ratio
        );
    }

    private record Stats(double min, double median, double p95, double mean, double max) {}
}
