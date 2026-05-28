package com.rpgmaster.app.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Filesystem allowlist for the dev-only {@code /v1/ingest} endpoint.
 *
 * <p>The endpoint accepts a server-side path from the request body, which is a
 * path-traversal / arbitrary-file-read risk if exposed. See ADR-013. Any path
 * that does not, after normalization, start with one of {@code allowedRoots}
 * must be rejected before it reaches {@link com.rpgmaster.app.application.IngestionUseCase}.
 *
 * @param allowedRoots absolute directory prefixes the controller is allowed to read from.
 *                     Empty list means "deny everything" — production-safe default.
 */
@ConfigurationProperties(prefix = "rpg.ingestion")
public record IngestionProperties(List<String> allowedRoots) {

    public IngestionProperties {
        allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
    }
}
