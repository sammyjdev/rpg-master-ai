package com.rpgmaster.app.application.port;

import java.util.List;

import com.rpgmaster.domain.Chunk;

/**
 * Port for splitting raw text into domain Chunk objects.
 * Adapter: PdfBoxChunkingAdapter.
 */
public interface ChunkingPort {

    /**
     * Configuration for the chunking strategy.
     *
     * @param maxTokens    Maximum approximate tokens per chunk (default: 200)
     * @param overlapTokens Number of tokens to overlap between chunks (default: 40)
     */
    record ChunkConfig(int maxTokens, int overlapTokens) {
        /**
         * Defaults: 400 tokens / 80 overlap — big enough to hold a full spell block
         * or monster trait without splitting mid-rule. bge-m3 supports up to 8192 tokens
         * so this is well within the embedding model's context window.
         */
        public static ChunkConfig defaults() {
            return new ChunkConfig(400, 80);
        }
    }

    /**
     * Splits text extracted from a PDF page into overlapping chunks.
     *
     * @param text       raw text from a PDF page or section
     * @param pageNumber source page number (1-indexed)
     * @param documentId FK to the parent Document
     * @param rulebookId namespace slug for Qdrant payload
     * @param config     chunking configuration
     * @return ordered list of Chunk records (SequencedCollection compatible)
     */
    List<Chunk> chunk(String text, int pageNumber, String documentId, String rulebookId, ChunkConfig config);
}
