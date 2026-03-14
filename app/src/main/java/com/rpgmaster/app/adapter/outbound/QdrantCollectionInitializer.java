package com.rpgmaster.app.adapter.outbound;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CreateCollection;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.HnswConfigDiff;
import io.qdrant.client.grpc.Collections.OptimizersConfigDiff;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorsConfig;

/**
 * Ensures the Qdrant collection {@code rpg-chunks} exists with the correct
 * schema on application startup. Idempotent — skips creation if collection exists.
 *
 * <p>Collection spec:
 * <ul>
 *   <li>Vector size: 1024 (bge-m3 dimensions)</li>
 *   <li>Distance: Cosine</li>
 *   <li>Payload index: {@code rulebook_id} (Keyword) — required for fast filtering</li>
 * </ul>
 */
@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);
    private static final String COLLECTION = "rpg-chunks";
    private static final int VECTOR_SIZE = 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final QdrantClient qdrantClient;

    public QdrantCollectionInitializer(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var existing = qdrantClient.listCollectionsAsync().get();
        boolean exists = existing.contains(COLLECTION);

        if (exists) {
            log.info("Qdrant collection '{}' already exists — skipping creation", COLLECTION);
            return;
        }

        log.info("Creating Qdrant collection '{}' (size={}, distance=Cosine)", COLLECTION, VECTOR_SIZE);

        qdrantClient.createCollectionAsync(
                CreateCollection.newBuilder()
                        .setCollectionName(COLLECTION)
                        .setVectorsConfig(VectorsConfig.newBuilder()
                                .setParams(VectorParams.newBuilder()
                                        .setSize(VECTOR_SIZE)
                                        .setDistance(Distance.Cosine)
                                        .build())
                                .build())
                        // HNSW tuning: higher ef_construct = better recall at index time
                        // ef = 128 at search time balances speed vs accuracy for RAG workloads
                        .setHnswConfig(HnswConfigDiff.newBuilder()
                                .setEfConstruct(200)
                                .setM(16)
                                .build())
                        .setOptimizersConfig(OptimizersConfigDiff.newBuilder()
                                .setIndexingThreshold(20000)
                                .build())
                        .build(),
                TIMEOUT
        ).get();

        // Index rulebook_id for fast payload filtering — mandatory for multi-rulebook isolation
        qdrantClient.createPayloadIndexAsync(
                COLLECTION,
                "rulebook_id",
                PayloadSchemaType.Keyword,
                null,
                null,
                null,
                TIMEOUT
        ).get();

        log.info("Qdrant collection '{}' created with rulebook_id index", COLLECTION);
    }
}
