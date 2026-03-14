package com.rpgmaster.app.application;

import com.rpgmaster.app.application.port.ChunkingPort;
import com.rpgmaster.app.application.port.DocumentRepository;
import com.rpgmaster.app.application.port.ChunkingPort.ChunkConfig;
import com.rpgmaster.app.application.port.DocumentStoragePort;
import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.application.port.VectorStorePort.ChunkVector;
import com.rpgmaster.domain.Chunk;
import com.rpgmaster.domain.IngestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the full synchronous ingestion pipeline for Increment 1:
 * <ol>
 *   <li>Read PDF pages from local filesystem</li>
 *   <li>Split each page into chunks</li>
 *   <li>Batch-embed all chunks via Ollama</li>
 *   <li>Upsert vectors into Qdrant</li>
 *   <li>Persist document metadata in PostgreSQL</li>
 * </ol>
 *
 * <p>Returns a sealed {@link IngestionResult} — handle with exhaustive switch,
 * no {@code default} branch.
 */
@Service
public class IngestionUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestionUseCase.class);

    private final DocumentStoragePort storagePort;
    private final ChunkingPort chunkingPort;
    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final DocumentRepository documentRepository;

    public IngestionUseCase(DocumentStoragePort storagePort,
                            ChunkingPort chunkingPort,
                            EmbeddingPort embeddingPort,
                            VectorStorePort vectorStorePort,
                            DocumentRepository documentRepository) {
        this.storagePort = storagePort;
        this.chunkingPort = chunkingPort;
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
        this.documentRepository = documentRepository;
    }

    /**
     * Ingests a PDF file into Qdrant and PostgreSQL.
     *
     * @param pdfPath    path to the PDF on the local filesystem
     * @param rulebookId namespace slug — e.g. "dnd-5e-phb"
     * @return sealed IngestionResult (Success | Failed | Partial)
     */
    @Transactional
    public IngestionResult ingest(Path pdfPath, String rulebookId) {
        var documentId = UUID.randomUUID().toString();
        var filename = pdfPath.getFileName().toString();

        documentRepository.create(documentId, filename, rulebookId, Instant.now());

        try {
            log.info("Starting ingestion: document={}, rulebook={}", documentId, rulebookId);

            // Step 1: Read PDF pages
            var pages = storagePort.readPages(pdfPath);
            log.info("Read {} pages from {}", pages.size(), filename);

            // Step 2: Chunk all pages
            var config = ChunkConfig.defaults();
            var allChunks = new ArrayList<Chunk>();
            for (int i = 0; i < pages.size(); i++) {
                var pageChunks = chunkingPort.chunk(pages.get(i), i + 1, documentId, rulebookId, config);
                allChunks.addAll(pageChunks);
            }
            log.info("Produced {} chunks from {} pages", allChunks.size(), pages.size());

            if (allChunks.isEmpty()) {
                documentRepository.fail(documentId);
                return new IngestionResult.Failed(documentId, "No chunks produced from PDF", null);
            }

            // Step 3: Batch-embed all chunk texts
            var texts = allChunks.stream().map(Chunk::text).toList();
            var vectors = embeddingPort.embedBatch(texts);

            // Step 4: Build ChunkVector list and upsert to Qdrant
            var chunkVectors = buildChunkVectors(allChunks, vectors);
            vectorStorePort.upsert(chunkVectors);

            // Step 5: Persist success metadata
            documentRepository.complete(documentId, allChunks.size());

            log.info("Ingestion complete: {} chunks stored for document={}", allChunks.size(), documentId);
            return new IngestionResult.Success(documentId, allChunks.size());

        } catch (Exception e) {
            log.error("Ingestion failed for document={}: {}", documentId, e.getMessage(), e);
            documentRepository.fail(documentId);
            return new IngestionResult.Failed(documentId, e.getMessage(), e);
        }
    }

    private List<ChunkVector> buildChunkVectors(List<Chunk> chunks, List<List<Float>> vectors) {
        var result = new ArrayList<ChunkVector>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            result.add(new ChunkVector(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.rulebookId(),
                    chunk.text(),
                    chunk.pageNumber(),
                    vectors.get(i)
            ));
        }
        return result;
    }
}
