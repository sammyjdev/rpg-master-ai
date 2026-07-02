package com.rpgmaster.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.rpgmaster.app.adapter.outbound.TeiRerankAdapter;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

import org.springframework.web.client.RestClient;

/**
 * Verifies the reranker against a REAL TEI container serving bge-reranker-v2-m3.
 * Slow on first run (model download ~2GB); integration tier only, not CI-critical.
 * Run: ./gradlew :app:integrationTest --tests '*TeiRerankIntegrationTest' (requires Docker)
 *
 * <p>Pinned to {@code cpu-1.2}: {@code cpu-1.5}/{@code cpu-1.7} segfault loading the Candle
 * CPU backend under Colima's Rosetta amd64 emulation on Apple Silicon; {@code cpu-1.2} does not.
 */
@Testcontainers(disabledWithoutDocker = true)
class TeiRerankIntegrationTest {

    @Container
    static GenericContainer<?> tei = new GenericContainer<>("ghcr.io/huggingface/text-embeddings-inference:cpu-1.2")
            .withCommand("--model-id", "BAAI/bge-reranker-v2-m3")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/health").forPort(80).withStartupTimeout(Duration.ofMinutes(10)));

    private static SourceChunk chunk(String id, String text) {
        return new SourceChunk(id, text, 1, 0.5f, "dnd-5e-phb");
    }

    @Test
    @DisplayName("real TEI ranks the topically relevant chunk first")
    void ranksRelevantFirst() {
        String baseUrl = "http://" + tei.getHost() + ":" + tei.getMappedPort(80);
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", baseUrl);
        var adapter = new TeiRerankAdapter(RestClient.builder(), props);

        var candidates = List.of(
                chunk("weather", "The weather today is sunny and warm."),
                chunk("fireball", "Fireball: a bright streak flashes to a point and blossoms into flame, 8d6 fire damage."),
                chunk("cooking", "To bake bread, mix flour, water, yeast and salt."));

        var result = adapter.rerank("How much damage does a fireball deal?", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo("fireball");
    }
}
