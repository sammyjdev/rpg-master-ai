package com.rpgmaster.app.adapter.inbound;

import com.rpgmaster.app.application.IngestionUseCase;
import com.rpgmaster.domain.IngestionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Spring Shell command for ingesting a PDF rulebook.
 *
 * <p>Usage:
 * <pre>{@code
 * rpg:> ingest --path /path/to/dnd5e-phb.pdf --rulebook dnd-5e-phb
 * }</pre>
 */
@ShellComponent
public class IngestCommand {

    private static final Logger log = LoggerFactory.getLogger(IngestCommand.class);

    private final IngestionUseCase ingestionUseCase;

    public IngestCommand(IngestionUseCase ingestionUseCase) {
        this.ingestionUseCase = ingestionUseCase;
    }

    @ShellMethod(value = "Ingest a PDF rulebook into the RAG system", key = "ingest")
    public String ingest(
            @ShellOption(help = "Absolute path to the PDF file") String path,
            @ShellOption(help = "Rulebook ID slug (e.g. dnd-5e-phb)") String rulebook
    ) {
        var pdfPath = Path.of(path);

        if (!Files.exists(pdfPath)) {
            return "Error: File not found at path: " + path;
        }
        if (!path.toLowerCase().endsWith(".pdf")) {
            return "Error: File must be a PDF: " + path;
        }

        log.info("Ingesting {} as rulebook '{}'", path, rulebook);

        var result = ingestionUseCase.ingest(pdfPath, rulebook);

        // Exhaustive switch on sealed IngestionResult — Java 21 pattern matching
        return switch (result) {
            case IngestionResult.Success s ->
                    "✓ Success: %d chunks stored for document %s (rulebook: %s)"
                            .formatted(s.chunksStored(), s.documentId(), rulebook);
            case IngestionResult.Failed f ->
                    "✗ Failed: %s — %s".formatted(f.documentId(), f.reason());
            case IngestionResult.Partial p ->
                    "⚠ Partial: %d chunks stored, %d failed for document %s"
                            .formatted(p.chunksStored(), p.chunksFailed(), p.documentId());
        };
    }
}
