# ADR-007: Chunking Strategy 400 Tokens With 80 Token Overlap

## Status

Accepted

## Context

Smaller chunks made retrieval too brittle for spell descriptions, monster traits, and rules that span several paragraphs. Larger chunks risk polluting context if they grow too broad.

## Decision

Use a default chunking strategy of `400` approximate tokens with `80` token overlap.

## Consequences

- Full rule blocks are more likely to stay intact.
- Re-ingestion is required when chunk settings change.
- Context chunks are larger, so top-K must be managed carefully.
- The strategy is tuned to the current corpus, not guaranteed universal.
