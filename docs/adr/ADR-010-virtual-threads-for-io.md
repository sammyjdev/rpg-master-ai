# ADR-010: Virtual Threads For IO Bound Work

## Status

Accepted

## Context

The application performs blocking IO against PDFs, Qdrant, PostgreSQL, and local model endpoints. Step 1 values readable imperative code and wants to showcase deliberate Java 21 usage.

## Decision

Use Java 21 virtual threads for Spring async execution instead of building the Step 1 flow around reactive-only code or custom platform-thread pools.

## Consequences

- IO-heavy code stays readable and imperative.
- The system avoids thread-pool tuning as a primary design concern.
- Libraries that pin threads, such as some PDFBox paths, remain an operational consideration.
- The project demonstrates Java 21 in a meaningful architectural location.
