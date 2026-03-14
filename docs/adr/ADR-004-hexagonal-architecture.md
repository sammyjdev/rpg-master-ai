# ADR-004: Hexagonal Architecture

## Status

Accepted

## Context

The project integrates with PDFs, models, a vector store, and a relational database. These are likely to change across phases. Direct framework coupling inside business logic would make those changes expensive.

## Decision

Organize the system around ports and adapters, keeping the application layer independent from Spring AI, Qdrant, PDFBox, and JPA.

## Consequences

- Core use cases remain focused on orchestration and business rules.
- Infrastructure changes are localized to adapters.
- More interfaces and indirection are introduced up front.
- The repository becomes easier to review from an architecture perspective.
