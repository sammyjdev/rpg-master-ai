# ADR-013: Dev-Only Ingest Endpoint with Path Allowlist

## Status

Accepted

## Context

`POST /v1/ingest` (in `IngestionController`) accepts an arbitrary server-side filesystem path in the request body and reads it via `Path.of(request.path())`. The endpoint exists for local development — driving ingestion from Swagger UI while iterating on chunking and prompts — but it has no authentication, no rate limiting, and no path validation. A Phase 2 deployment that left it exposed would hand any caller arbitrary local file read against the server (the endpoint reaches `pdfPath.toFile().exists()` before any sanity check).

## Decision

Two layered guards, both enforced in code:

1. `@Profile("local")` on `IngestionController`. The bean is only registered when the `local` Spring profile is active. The existing dev runtime already uses `--spring.profiles.active=local,api` (see `application-local.yml.example` and `./rpgm start`), so this is a no-op for developers and a hard removal for any other deployment.
2. A request-time path allowlist driven by `rpg.ingestion.allowed-roots` (typed via `IngestionProperties`). Every incoming path is resolved to `toAbsolutePath().normalize()` and rejected with HTTP 400 unless it `startsWith` one of the configured roots. The default in `application-local.yml` is `${user.home}/rpg-corpus`. The default in `application.yml` is empty, so missing configuration fails closed.

## Consequences

- Production deploys (any profile set that does not include `local`) do not register the controller, so the route returns 404 — there is no path through the app that can read arbitrary files.
- Even within `local`, dropping a path under `/etc` or `~/.ssh` is rejected before the file is touched. Symlinks pointing outside the allowlist are still risky and remain a known limitation — Phase 2's multipart upload will retire this surface entirely.
- Developers must keep their PDFs under a configured root, or extend `rpg.ingestion.allowed-roots` in their local override.
- The behaviour is regression-tested by `IngestionControllerTest` (inside, outside, dot-dot traversal, empty allowlist).
