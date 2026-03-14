package com.rpgmaster.app.adapter.outbound;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.PointIdFactory.id;
import io.qdrant.client.QdrantClient;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;

/**
 * Vector store adapter backed by Qdrant.
 * Collection: {@code rpg-chunks}, vector size: 1024 (bge-m3).
 *
 * <p>Upsert is idempotent — same {@code chunkId} overwrites the existing point.
 * Always filters by {@code rulebook_id} to prevent cross-rulebook contamination.
 */
@Component
public class QdrantVectorStoreAdapter implements VectorStorePort {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreAdapter.class);
    private static final String COLLECTION = "rpg-chunks";
    private static final String RULEBOOK_ID_FIELD = "rulebook_id";

    private final QdrantClient qdrantClient;

    public QdrantVectorStoreAdapter(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    /** {@inheritDoc} */
    @Override
    public void upsert(List<ChunkVector> chunks) {
        if (chunks.isEmpty()) return;

        var points = chunks.stream()
                .map(this::toPointStruct)
                .toList();

        try {
            var result = qdrantClient.upsertAsync(COLLECTION, points).get();
            log.info("Upserted {} points to Qdrant, status={}", points.size(), result.getStatus());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Qdrant upsert", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant upsert failed", e.getCause());
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<SourceChunk> search(String rulebookId, List<Float> queryVector, int topK, float threshold) {
        try {
            var searchBuilder = Points.SearchPoints.newBuilder()
                    .setCollectionName(COLLECTION)
                    .addAllVector(queryVector)
                    .setLimit(topK)
                    .setScoreThreshold(threshold)
                    .setWithPayload(enable(true))
                    // ef=128 at search time: better recall at minimal latency cost
                    .setParams(Points.SearchParams.newBuilder()
                            .setHnswEf(128)
                            .setExact(false)
                            .build());

            if (rulebookId != null) {
                var filter = Points.Filter.newBuilder()
                        .addMust(matchKeyword(RULEBOOK_ID_FIELD, rulebookId))
                        .build();
                searchBuilder.setFilter(filter);
            }

            return qdrantClient.searchAsync(searchBuilder.build()).get().stream()
                    .map(scored -> new SourceChunk(
                            scored.getId().getUuid(),
                            scored.getPayload().get("text").getStringValue(),
                            (int) scored.getPayload().get("page_number").getIntegerValue(),
                            scored.getScore(),
                            scored.getPayload().get(RULEBOOK_ID_FIELD).getStringValue()
                    ))
                    .toList();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during Qdrant search", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Qdrant search failed", e.getCause());
        }
    }

    private PointStruct toPointStruct(ChunkVector chunk) {
        return PointStruct.newBuilder()
                .setId(id(UUID.fromString(chunk.chunkId())))
                .setVectors(vectors(chunk.vector()))
                .putAllPayload(Map.of(
                        "text",        value(chunk.text()),
                        "document_id", value(chunk.documentId()),
                        RULEBOOK_ID_FIELD, value(chunk.rulebookId()),
                        "page_number", value((long) chunk.pageNumber())
                ))
                .build();
    }
}
