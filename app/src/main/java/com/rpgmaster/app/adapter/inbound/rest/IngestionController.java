package com.rpgmaster.app.adapter.inbound.rest;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rpgmaster.app.application.IngestionUseCase;
import com.rpgmaster.domain.IngestionResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST adapter for triggering PDF ingestion while the API server is running.
 * Accepts a local filesystem path — suitable for local/dev use only.
 *
 * <p>In Phase 2+ this will accept multipart upload or S3 key instead.
 */
@Tag(name = "Ingestion", description = "PDF rulebook ingestion (local dev only)")
@RestController
@RequestMapping("/v1")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final IngestionUseCase ingestionUseCase;

    public IngestionController(IngestionUseCase ingestionUseCase) {
        this.ingestionUseCase = ingestionUseCase;
    }

    /**
     * Triggers ingestion of a local PDF file.
     *
     * @param request path to PDF on local filesystem and target rulebook ID
     * @return ingestion result summary
     */
    @Operation(summary = "Ingest a local PDF",
               description = "Reads a PDF from the local filesystem, chunks it, embeds the chunks, "
                       + "and stores them in Qdrant. Dev-only — Phase 2 will accept multipart upload.")
    @ApiResponse(responseCode = "200", description = "Ingestion result")
    @ApiResponse(responseCode = "400", description = "Invalid request or file not found")
    @PostMapping("/ingest")
    public ResponseEntity<IngestionResponse> ingest(@RequestBody IngestionRequest request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        if (request.rulebookId() == null || request.rulebookId().isBlank()) {
            throw new IllegalArgumentException("rulebookId is required");
        }

        var pdfPath = Path.of(request.path());
        if (!pdfPath.toFile().exists()) {
            throw new IllegalArgumentException("File not found: " + request.path());
        }

        log.info("REST ingest triggered: path={}, rulebook={}", request.path(), request.rulebookId());
        var result = ingestionUseCase.ingest(pdfPath, request.rulebookId());

        var response = switch (result) {
            case IngestionResult.Success s -> new IngestionResponse(
                    s.documentId(), "success", s.chunksStored(), null);
            case IngestionResult.Failed f -> new IngestionResponse(
                    f.documentId(), "failed", 0, f.reason());
            case IngestionResult.Partial p -> new IngestionResponse(
                    p.documentId(), "partial", p.chunksStored(),
                    "Failed chunks: " + p.chunksFailed());
        };

        return ResponseEntity.ok(response);
    }

    @Schema(description = "Ingestion request with local file path")
    public record IngestionRequest(
            @Schema(description = "Absolute path to the PDF on the server filesystem", example = "C:/pdfs/phb.pdf")
            String path,
            @Schema(description = "Rulebook identifier for namespace isolation", example = "dnd-5e-phb")
            String rulebookId) {}

    @Schema(description = "Ingestion result summary")
    public record IngestionResponse(
            @Schema(description = "UUID of the ingested document")
            String documentId,
            @Schema(description = "Ingestion outcome", allowableValues = {"success", "failed", "partial"})
            String status,
            @Schema(description = "Number of chunks stored in the vector database")
            int chunksStored,
            @Schema(description = "Error details if status is failed or partial")
            String error) {}
}
