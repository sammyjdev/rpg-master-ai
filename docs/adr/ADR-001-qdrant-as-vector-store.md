# ADR-001: Qdrant As Vector Store

## Status

Accepted

## Context

The project needs a local-first vector database with payload filtering, predictable developer setup, and a Java client that can be used from a Spring Boot application without leaking vendor concerns into the application layer.

## Decision

Use Qdrant as the vector store behind `VectorStorePort`.

## Consequences

- Payload filtering by `rulebook_id` supports multi-rulebook isolation.
- Local Docker setup is straightforward.
- HNSW search tuning is available through the Java client.
- The application now owns some vector-search tuning responsibility instead of delegating that entirely to a managed service.
