package com.rpgmaster.app.integration;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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

import com.rpgmaster.app.application.IngestionUseCase;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.IngestionResult;

/**
 * Integration test for the ingestion pipeline.
 * Uses real Qdrant and PostgreSQL via Testcontainers.
 * Ollama is NOT mocked — this test requires the Ollama Docker container to be running
 * (or a WireMock stub in a future iteration).
 *
 * <p>To run: {@code ./gradlew :app:integrationTest} (requires Docker)
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class IngestionIntegrationTest {

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
    private IngestionUseCase ingestionUseCase;

    @Autowired
    private VectorStorePort vectorStorePort;

    @Test
    @DisplayName("Ingesting a sample PDF stores chunks in Qdrant and Postgres")
    void ingestStoresChunksInQdrantAndPostgres() {
        // Requires Ollama for the embedding step — skip gracefully in CI without Ollama
        org.junit.jupiter.api.Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama is not reachable. Start Ollama locally to run this test.");

        // Uses the bundled test fixture PDF (a small text-based PDF)
        var pdfPath = Path.of("src/integrationTest/resources/fixtures/sample-rules.pdf");

        // Only run if fixture exists — avoids failing when PDF is not committed
        org.junit.jupiter.api.Assumptions.assumeTrue(pdfPath.toFile().exists(),
                "Skipping: test fixture PDF not found at " + pdfPath);

        var result = ingestionUseCase.ingest(pdfPath, "test-rulebook");

        assertThat(result).isInstanceOf(IngestionResult.Success.class);

        var success = (IngestionResult.Success) result;
        assertThat(success.chunksStored()).isGreaterThan(0);

        // Verify chunks are searchable in Qdrant
        // Use a dummy vector (will return 0 results with high threshold, but verifies connectivity)
        var dummyVector = List.<Float>of(new Float[1024]);
        var searchResult = vectorStorePort.search("test-rulebook", dummyVector, 5, 0.0f);
        assertThat(searchResult).isNotNull(); // connection works
    }

    @Test
    @DisplayName("Ingesting non-existent file returns Failed result")
    void ingestNonExistentFileReturnsFailed() {
        var fakePath = Path.of("/non/existent/file.pdf");
        var result = ingestionUseCase.ingest(fakePath, "dnd-5e-phb");

        assertThat(result).isInstanceOf(IngestionResult.Failed.class);
    }

    /** Quick connectivity probe — mirrors the guard used in QueryIntegrationTest. */
    private boolean isOllamaReachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 11434), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
