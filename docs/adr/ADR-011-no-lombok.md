# ADR-011: No Lombok

## Status

Accepted

## Context

The codebase wants explicit APIs, clear constructors, and strong alignment with Java 21 records. Lombok would reduce boilerplate, but it would also add indirection and annotation-driven code generation.

## Decision

Do not use Lombok. Use records for immutable domain types and explicit constructors or accessors where mutable infrastructure types are necessary.

## Consequences

- The compiled shape of the code remains obvious to reviewers.
- Domain models stay aligned with Java 21 records.
- Some infrastructure classes remain slightly more verbose.
- The project avoids another annotation processor and its associated magic.
