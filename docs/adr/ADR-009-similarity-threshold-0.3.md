# ADR-009: Default Similarity Threshold 0.3

## Status

Accepted

## Context

Higher similarity thresholds filtered out relevant chunks in multilingual and OCR-noisy scenarios. Strict precision hurt answer quality more than a few weaker chunks did.

## Decision

Use `0.3` as the default similarity threshold for Step 1 queries.

## Consequences

- Recall improves, especially for cross-lingual lookups.
- Some less relevant chunks may appear in the retrieved set.
- Threshold choice must be validated alongside top-K and chunk size.
- Future reranking can recover precision without losing recall.
