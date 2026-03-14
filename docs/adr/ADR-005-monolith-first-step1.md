# ADR-005: Monolith First For Step 1

## Status

Accepted

## Context

The long-term vision includes multiple services, but Step 1 must prove the end-to-end RAG loop quickly: ingestion, retrieval, and answer generation.

## Decision

Implement Step 1 as a monolith with two Gradle modules: `shared-domain` and `app`.

## Consequences

- Delivery speed is higher because cross-service coordination is avoided.
- Local debugging and benchmarking are simpler.
- Some interfaces are development-oriented, such as local filesystem ingestion.
- Future extraction into separate services remains possible because the use cases already sit behind ports.
