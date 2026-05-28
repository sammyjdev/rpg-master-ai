package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.rpgmaster.app.adapter.inbound.rest.IngestionController;
import com.rpgmaster.app.adapter.inbound.rest.IngestionController.IngestionRequest;
import com.rpgmaster.app.application.IngestionUseCase;
import com.rpgmaster.app.config.IngestionProperties;
import com.rpgmaster.domain.IngestionResult;

/**
 * Verifies the ADR-013 path-traversal guard on the dev-only ingestion endpoint.
 *
 * <p>Drives the controller directly rather than spinning up a full WebFlux slice —
 * the validation logic lives in the controller's plain Java method, not in any
 * Spring infrastructure, so a unit test is enough and avoids loading the whole
 * application context (Postgres, Qdrant, Ollama configs).
 */
class IngestionControllerTest {

    @Test
    void rejects_path_outside_allowlist(@TempDir Path allowed, @TempDir Path forbidden) throws Exception {
        var useCase = mock(IngestionUseCase.class);
        var controller = new IngestionController(
                useCase,
                new IngestionProperties(List.of(allowed.toString())));

        var outside = forbidden.resolve("evil.pdf");
        Files.writeString(outside, "%PDF-1.4\nfake");

        var thrown = catchThrowable(() ->
                controller.ingest(new IngestionRequest(outside.toString(), "dnd-5e-phb")));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-roots");
        verify(useCase, never()).ingest(any(), any());
    }

    @Test
    void rejects_path_traversal_via_dotdot(@TempDir Path allowed, @TempDir Path forbidden) throws Exception {
        var useCase = mock(IngestionUseCase.class);
        var controller = new IngestionController(
                useCase,
                new IngestionProperties(List.of(allowed.toString())));

        var outside = forbidden.resolve("secrets.pdf");
        Files.writeString(outside, "%PDF-1.4\nfake");

        // Build a traversal path that textually starts under `allowed` but normalizes
        // out of it: <allowed>/../<forbidden-name>/secrets.pdf
        var traversal = allowed.resolve("..").resolve(forbidden.getFileName()).resolve("secrets.pdf");

        var thrown = catchThrowable(() ->
                controller.ingest(new IngestionRequest(traversal.toString(), "dnd-5e-phb")));

        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-roots");
        verify(useCase, never()).ingest(any(), any());
    }

    @Test
    void accepts_path_inside_allowlist(@TempDir Path allowed) throws Exception {
        var useCase = mock(IngestionUseCase.class);
        var inside = allowed.resolve("phb.pdf");
        Files.writeString(inside, "%PDF-1.4\nfake");

        var docId = UUID.randomUUID().toString();
        when(useCase.ingest(any(Path.class), eq("dnd-5e-phb")))
                .thenReturn(new IngestionResult.Success(docId, 42));

        var controller = new IngestionController(
                useCase,
                new IngestionProperties(List.of(allowed.toString())));

        var response = controller.ingest(new IngestionRequest(inside.toString(), "dnd-5e-phb"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo("success");
        assertThat(response.getBody().chunksStored()).isEqualTo(42);
    }

    @Test
    void empty_allowlist_rejects_everything(@TempDir Path anywhere) throws Exception {
        var useCase = mock(IngestionUseCase.class);
        var file = anywhere.resolve("phb.pdf");
        Files.writeString(file, "%PDF-1.4\nfake");

        var controller = new IngestionController(useCase, new IngestionProperties(List.of()));

        var thrown = catchThrowable(() ->
                controller.ingest(new IngestionRequest(file.toString(), "dnd-5e-phb")));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verify(useCase, never()).ingest(any(), any());
    }
}
