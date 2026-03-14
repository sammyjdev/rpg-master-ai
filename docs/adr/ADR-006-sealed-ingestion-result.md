# ADR-006: Sealed IngestionResult

## Status

Accepted

## Context

The ingestion flow can complete successfully, fail entirely, or partially succeed. Using exceptions or magic strings would weaken exhaustiveness and make handling less explicit.

## Decision

Represent ingestion outcomes as the sealed interface `IngestionResult` with `Success`, `Failed`, and `Partial` record variants.

## Consequences

- Call sites can use exhaustive `switch` expressions with compiler support.
- Domain states are clearer than ad hoc status strings.
- The design leans into Java 21 language features deliberately.
