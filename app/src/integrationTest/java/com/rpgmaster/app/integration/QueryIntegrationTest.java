package com.rpgmaster.app.integration;

import java.util.ArrayList;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import com.rpgmaster.app.application.QueryUseCase;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.QueryRequest;

/**
 * Integration test for the query pipeline.
 * Uses real Qdrant and PostgreSQL via Testcontainers.
 *
 * <p>LLM responses rely on Ollama (or a WireMock stub if added). The embedded
 * chunks are pre-seeded directly via {@link VectorStorePort} to avoid a full
 * ingestion pipeline dependency in query tests.
 *
 * <p>To run: {@code ./gradlew :app:integrationTest} (requires Docker)
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class QueryIntegrationTest {

    private static final String DND_RULEBOOK_ID = "dnd-5e-phb";

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
    private QueryUseCase queryUseCase;

    @Autowired
    private VectorStorePort vectorStorePort;

    @BeforeEach
    @SuppressWarnings("unused")
    void seedChunks() {
        // Pre-load a known vector for "dnd-5e-phb" rulebook
        // This synthetic vector will be found when queried with a near-zero vector and low threshold
        var syntheticVector = buildSyntheticVector(1024, 0.01f);
        var chunk = new VectorStorePort.ChunkVector(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                DND_RULEBOOK_ID,
                "A fireball is a 3rd-level evocation spell that deals 8d6 fire damage.",
                1,
                syntheticVector
        );
        vectorStorePort.upsert(List.of(chunk));
    }

    @Test
    @DisplayName("Query returns a result with sources from the correct rulebook")
    void queryRulebookScopedReturnsSources() {
        // Ollama must be reachable for this test (or stub it via WireMock in a future iteration)
        org.junit.jupiter.api.Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama is not reachable. Start Docker Compose to run this test.");

        var request = new QueryRequest("What is a Fireball spell?", DND_RULEBOOK_ID);
        var result = queryUseCase.query(request);

        assertThat(result).isNotNull();
        assertThat(result.answer()).isNotBlank();
        assertThat(result.sources()).isNotEmpty();
        assertThat(result.sources()).allMatch(s -> DND_RULEBOOK_ID.equals(s.rulebookId()));
    }

    @Test
    @DisplayName("Query with cross-rulebook isolation never returns chunks from another rulebook")
    void queryIsolatesRulebookChunks() {
        // Seed a chunk for a different rulebook
        var otherVector = buildSyntheticVector(1024, 0.01f);
        var otherChunk = new VectorStorePort.ChunkVector(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "pathfinder-2e-core",
                "This chunk belongs to Pathfinder, not D&D.",
                2,
                otherVector
        );
        vectorStorePort.upsert(List.of(otherChunk));

        // When querying "dnd-5e-phb", Pathfinder chunks must never surface
        var dndRulebookVector = buildSyntheticVector(1024, 0.01f);
        var results = vectorStorePort.search(DND_RULEBOOK_ID, dndRulebookVector, 10, 0.0f);

        assertThat(results)
                .extracting(com.rpgmaster.domain.SourceChunk::rulebookId)
                .doesNotContain("pathfinder-2e-core");
    }

    @Test
    @DisplayName("Query with no matching chunks returns gracefully")
    void queryNoMatchesReturnsEmptyAnswer() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama is not reachable. Start Docker Compose to run this test.");

        var request = new QueryRequest("What is the meaning of life in Starfinder?", "nonexistent-rulebook");
        var result = queryUseCase.query(request);

        assertThat(result).isNotNull();
        // No sources for a rulebook that has no vectors
        assertThat(result.sources()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Float> buildSyntheticVector(int dimensions, float value) {
        List<Float> vector = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            vector.add(value);
        }
        return vector;
    }

    /** Quick connectivity probe — avoids hard-failing tests when Ollama is not running. */
    private boolean isOllamaReachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 11434), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
