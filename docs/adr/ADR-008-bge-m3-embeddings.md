# ADR-008: bge-m3 For Embeddings

## Status

Accepted

## Context

The corpus and the user questions may not always share the same language. Retrieval quality degraded when the embedding model favored English too strongly.

## Decision

Use `bge-m3` as the local embedding model for Step 1.

## Consequences

- Multilingual retrieval quality improves materially.
- Vector dimensionality and resource usage increase.
- Re-ingestion is required whenever the embedding model changes.
- The system is better aligned with English and Portuguese query behavior.
