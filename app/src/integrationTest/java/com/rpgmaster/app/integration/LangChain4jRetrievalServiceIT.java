package com.rpgmaster.app.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RetrievalPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

/**
 * Compares the Spring AI and LangChain4j retrieval paths against the SAME Qdrant
 * collection, seeded with real bge-m3 embeddings. Asserts result overlap > 80%.
 *
 * <p>Requires a local Ollama with {@code bge-m3} (localhost:11434). Skips otherwise.
 * To run: {@code ./gradlew :app:integrationTest --tests "*LangChain4jRetrievalServiceIT"}
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class LangChain4jRetrievalServiceIT {

    private static final String RULEBOOK_ID = "dnd-5e-phb";
    private static final int TOP_K = 5;
    private static final float THRESHOLD = 0.0f;
    private static final double MIN_OVERLAP = 0.80;

    private static final List<String> CORPUS = List.of(
            "A fireball is a 3rd-level evocation spell that deals 8d6 fire damage in a 20-foot radius.",
            "Counterspell is a 3rd-level abjuration spell that interrupts a creature casting a spell.",
            "A longsword is a martial melee weapon dealing 1d8 slashing damage, or 1d10 when wielded two-handed.",
            "The Barbarian's Rage grants advantage on Strength checks and resistance to physical damage.",
            "A Healing Potion restores 2d4 + 2 hit points when a creature drinks it as an action.",
            "Sneak Attack lets a Rogue deal extra damage when they have advantage on the attack roll.",
            "Darkvision allows a creature to see in dim light within 60 feet as if it were bright light.",
            "Armor Class represents how hard a creature is to hit; plate armor grants AC 18."
    );

    private static final List<String> QUERIES = List.of(
            "How much damage does a fireball deal?",
            "How do I stop an enemy from casting a spell?",
            "What weapon damage does a longsword do?",
            "How does a barbarian's rage work?",
            "How many hit points does a healing potion restore?",
            "When can a rogue use sneak attack?"
    );

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

    @Autowired
    private VectorStorePort vectorStorePort;

    @Autowired
    @Qualifier("springAiRetrievalService")
    private RetrievalPort springAiRetrieval;

    @Autowired
    @Qualifier("langChain4jRetrievalService")
    private RetrievalPort langChain4jRetrieval;

    @BeforeEach
    @SuppressWarnings("unused")
    void seedRealEmbeddings() {
        Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama (bge-m3) not reachable on localhost:11434.");

        var vectors = embeddingPort.embedBatch(CORPUS);
        var chunks = new ArrayList<VectorStorePort.ChunkVector>(CORPUS.size());
        for (int i = 0; i < CORPUS.size(); i++) {
            chunks.add(new VectorStorePort.ChunkVector(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    RULEBOOK_ID,
                    CORPUS.get(i),
                    i + 1,
                    vectors.get(i)
            ));
        }
        vectorStorePort.upsert(chunks);
    }

    @Test
    @DisplayName("Spring AI and LangChain4j retrieval overlap > 80% on the same query set")
    void retrievalPathsOverlapAboveThreshold() {
        Assumptions.assumeTrue(isOllamaReachable(),
                "Skipping: Ollama (bge-m3) not reachable on localhost:11434.");

        double totalOverlap = 0.0;
        for (String query : QUERIES) {
            Set<String> springIds = idSet(springAiRetrieval.retrieve(RULEBOOK_ID, query, TOP_K, THRESHOLD));
            Set<String> l4jIds = idSet(langChain4jRetrieval.retrieve(RULEBOOK_ID, query, TOP_K, THRESHOLD));
            totalOverlap += jaccard(springIds, l4jIds);
        }
        double meanOverlap = totalOverlap / QUERIES.size();

        assertThat(meanOverlap)
                .as("mean Jaccard overlap of top-%d chunk ids across %d queries", TOP_K, QUERIES.size())
                .isGreaterThan(MIN_OVERLAP);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Set<String> idSet(List<SourceChunk> chunks) {
        return chunks.stream().map(SourceChunk::chunkId).collect(Collectors.toCollection(HashSet::new));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        var intersection = new HashSet<>(a);
        intersection.retainAll(b);
        var union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private boolean isOllamaReachable() {
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", 11434), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
