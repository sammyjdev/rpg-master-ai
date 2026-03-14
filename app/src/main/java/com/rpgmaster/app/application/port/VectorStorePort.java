package com.rpgmaster.app.application.port;

import com.rpgmaster.domain.SourceChunk;

import java.util.List;

/**
 * Port for vector storage operations — upsert and similarity search.
 * Adapter: QdrantVectorStoreAdapter.
 */
public interface VectorStorePort {

    /**
     * Represents a chunk with its pre-computed embedding vector, ready to upsert.
     *
     * @param chunkId    UUID — Qdrant point ID (idempotent upsert)
     * @param documentId FK to the parent document
     * @param rulebookId Namespace for payload filter
     * @param text       Chunk text stored as payload
     * @param pageNumber Source page number stored as payload
     * @param vector     Pre-computed embedding vector
     */
    record ChunkVector(
            String chunkId,
            String documentId,
            String rulebookId,
            String text,
            int pageNumber,
            List<Float> vector
    ) {}

    /**
     * Upserts a batch of chunk vectors into the vector store.
     * Idempotent — same chunkId overwrites the existing point.
     *
     * @param chunks list of chunks with their vectors to store
     */
    void upsert(List<ChunkVector> chunks);

    /**
     * Searches for the most similar chunks to a query vector.
     * Always filters by rulebookId unless null (cross-rulebook search).
     *
     * @param rulebookId   namespace filter; null = search across all rulebooks
     * @param queryVector  the embedded query vector
     * @param topK         maximum number of results to return
     * @param threshold    minimum cosine similarity score (0.0–1.0)
     * @return scored chunks ordered by descending similarity
     */
    List<SourceChunk> search(String rulebookId, List<Float> queryVector, int topK, float threshold);
}
