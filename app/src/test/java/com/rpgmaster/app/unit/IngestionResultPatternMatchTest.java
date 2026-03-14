package com.rpgmaster.app.unit;

import com.rpgmaster.domain.IngestionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates Java 21 sealed interface + pattern matching switch.
 * Pure Java — no Spring context, no I/O.
 *
 * This test class is a portfolio showcase: it demonstrates that sealed types
 * and exhaustive switch work exactly as designed.
 */
class IngestionResultPatternMatchTest {

    @Test
    @DisplayName("Success result: pattern match extracts documentId and chunksStored")
    void success_patternMatchExtractsFields() {
        IngestionResult result = new IngestionResult.Success("doc-123", 42);

        var message = describeResult(result);

        assertThat(message).contains("42 chunks");
        assertThat(message).contains("doc-123");
    }

    @Test
    @DisplayName("Failed result: pattern match extracts documentId and reason")
    void failed_patternMatchExtractsReason() {
        IngestionResult result = new IngestionResult.Failed("doc-456", "PDF is corrupt", null);

        var message = describeResult(result);

        assertThat(message).contains("PDF is corrupt");
        assertThat(message).contains("doc-456");
    }

    @Test
    @DisplayName("Partial result: pattern match extracts chunksStored and chunksFailed")
    void partial_patternMatchExtractsCounts() {
        IngestionResult result = new IngestionResult.Partial("doc-789", 30, 5);

        var message = describeResult(result);

        assertThat(message).contains("30");
        assertThat(message).contains("5");
    }

    @Test
    @DisplayName("Success is the correct sealed type — not Failed or Partial")
    void sealedType_correctSubtype() {
        IngestionResult result = new IngestionResult.Success("doc-1", 10);

        assertThat(result).isInstanceOf(IngestionResult.Success.class);
        assertThat(result).isNotInstanceOf(IngestionResult.Failed.class);
        assertThat(result).isNotInstanceOf(IngestionResult.Partial.class);
    }

    /**
     * Exhaustive switch on sealed IngestionResult — Java 21 pattern matching.
     * No default branch — compiler enforces exhaustiveness.
     */
    private String describeResult(IngestionResult result) {
        return switch (result) {
            case IngestionResult.Success s ->
                    "Success: %d chunks stored for document %s".formatted(s.chunksStored(), s.documentId());
            case IngestionResult.Failed f ->
                    "Failed: document %s — %s".formatted(f.documentId(), f.reason());
            case IngestionResult.Partial p ->
                    "Partial: %d stored, %d failed for document %s"
                            .formatted(p.chunksStored(), p.chunksFailed(), p.documentId());
        };
    }
}
