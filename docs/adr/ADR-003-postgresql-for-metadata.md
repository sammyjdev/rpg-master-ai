# ADR-003: PostgreSQL For Metadata

## Status

Accepted

## Context

Rulebook ingestion needs durable metadata for document lifecycle, chunk counts, and future operational workflows. Qdrant alone is not the right place for transactional document state.

## Decision

Use PostgreSQL to persist document metadata and lifecycle transitions, with Flyway managing schema evolution.

## Consequences

- Document state remains queryable and durable independent of vector storage.
- Future ingestion workflows can build on a reliable relational model.
- The system must maintain consistency across two stores: PostgreSQL and Qdrant.
