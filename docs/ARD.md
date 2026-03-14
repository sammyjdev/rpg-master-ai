# Architecture Requirements Document

## 1. System Overview

RPG Master AI is a Java 21 RAG application that ingests RPG rulebooks in PDF format, stores chunk embeddings in Qdrant, persists document metadata in PostgreSQL, and answers natural language questions through a CLI and an OpenAI-compatible REST API. The current implementation is Step 1: a monolith split into two Gradle modules, `shared-domain` and `app`.

## 2. Scope

### In Scope

- Synchronous ingestion from local PDF files
- Chunking and embedding generation
- Vector storage and similarity search
- RAG query orchestration
- OpenAI-compatible REST API and Spring Shell CLI
- Local development with Docker Compose

### Out of Scope

- Multi-service deployment
- Async ingestion with Kafka
- Public authentication and authorization
- Production cloud infrastructure
- Semantic cache and observability dashboards

## 3. Stakeholders And Concerns

| Stakeholder                 | Concern                                                                        |
| --------------------------- | ------------------------------------------------------------------------------ |
| Developer / Portfolio Owner | Demonstrate solid Java 21 and AI engineering decisions with visible trade-offs |
| Recruiter / Hiring Manager  | Evaluate architecture clarity, delivery discipline, and production thinking    |
| Senior Engineer Reviewer    | Validate boundaries, testability, maintainability, and quality attributes      |
| End User                    | Ask rulebook questions and receive grounded answers with citations             |

## 4. Architectural Drivers

### Functional Requirements

- Ingest a rulebook PDF and persist document lifecycle state.
- Split extracted text into stable, overlapping chunks.
- Generate embeddings for chunks and questions.
- Search rulebook-scoped vectors by semantic similarity.
- Build LLM context with source and page metadata.
- Return grounded answers through CLI and REST.

### Quality Attribute Requirements

| Attribute            | Requirement                                                                                                                       | Rationale                                                            |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Performance          | Local query latency should stay below 5 seconds for common questions on warmed infrastructure.                                    | Keep demos and interactive use viable with Ollama.                   |
| Ingestion Throughput | A single rulebook ingestion should complete without manual intervention and remain operationally acceptable on a dev workstation. | Step 1 optimizes for developer flow, not bulk ingestion.             |
| Reliability          | Document status transitions must be persisted consistently as `PENDING`, `COMPLETED`, or `FAILED`.                                | Metadata must remain trustworthy even if embedding or storage fails. |
| Accuracy             | Answers must be grounded in retrieved chunks and cite page numbers whenever the answer is found.                                  | The system is a rulebook assistant, not a creative chatbot.          |
| Maintainability      | Domain logic must remain isolated from frameworks and external systems through ports.                                             | Preserve refactorability and adapter swap capability.                |
| Testability          | Core business logic should be unit-testable without Spring; infra paths should be integration-tested with real containers.        | Avoid over-mocking AI and vector infrastructure.                     |
| Portability          | Local models and future production models must be swappable through adapter and config changes, not use case changes.             | Protect the core application from vendor lock-in.                    |
| Operability          | Local setup should be reproducible with Docker Compose and Gradle only.                                                           | Minimize onboarding friction for demos and portfolio review.         |
| Security             | Step 1 may trade production-hardening for delivery speed, but all deviations must be explicit and documented.                     | Keep dev shortcuts visible and bounded.                              |

## 5. Current Architecture

### Module Structure

- `shared-domain`: zero-dependency domain records and sealed interfaces
- `app`: monolith containing use cases, inbound adapters, outbound adapters, and configuration

### Runtime Dependencies

- Qdrant for vector storage
- PostgreSQL for metadata
- Ollama for embeddings and chat models
- Docker Compose for local environment orchestration

### Architectural Style

The application uses hexagonal architecture:

- Inbound adapters: Spring Shell commands and REST controllers
- Application layer: `IngestionUseCase` and `QueryUseCase`
- Outbound adapters: PDFBox, Spring AI, Qdrant, PostgreSQL
- Domain layer: records and sealed result types in `shared-domain`

## 6. Constraints

- Java 21 only
- Spring Boot 3.3+
- Gradle multi-module build only
- No Lombok
- Hexagonal boundaries must be preserved
- No ORM-heavy business logic shortcuts
- Integration tests should use real infrastructure via Testcontainers

## 7. Key Risks

| Risk                                         | Current Handling                                                      |
| -------------------------------------------- | --------------------------------------------------------------------- |
| Multilingual retrieval quality               | `bge-m3` embeddings plus lower threshold and language-aware prompting |
| Small model answer quality                   | Upgraded local chat model to `qwen2.5:7b`                             |
| PDF extraction noise                         | Boilerplate stripping in `PdfBoxChunkingAdapter`                      |
| Context pollution                            | Rulebook payload filtering and top-K limits                           |
| Dev-only shortcuts leaking into later phases | Documented in ADRs and implementation journal                         |

## 8. Architectural Decision Links

- [ADR-001 Qdrant As Vector Store](adr/ADR-001-qdrant-as-vector-store.md)
- [ADR-002 Ollama For Local Models](adr/ADR-002-ollama-for-local-models.md)
- [ADR-003 PostgreSQL For Metadata](adr/ADR-003-postgresql-for-metadata.md)
- [ADR-004 Hexagonal Architecture](adr/ADR-004-hexagonal-architecture.md)
- [ADR-005 Monolith First For Step 1](adr/ADR-005-monolith-first-step1.md)
- [ADR-006 Sealed IngestionResult](adr/ADR-006-sealed-ingestion-result.md)
- [ADR-007 Chunking Strategy 400-80](adr/ADR-007-chunking-strategy-400-80.md)
- [ADR-008 bge-m3 For Embeddings](adr/ADR-008-bge-m3-embeddings.md)
- [ADR-009 Similarity Threshold 0.3](adr/ADR-009-similarity-threshold-0.3.md)
- [ADR-010 Virtual Threads For IO](adr/ADR-010-virtual-threads-for-io.md)
- [ADR-011 No Lombok](adr/ADR-011-no-lombok.md)

## 9. Acceptance Criteria For Step 1

- A reviewer can run the full local stack using Docker Compose and Gradle.
- At least one rulebook can be ingested end-to-end without manual database intervention.
- Questions return grounded answers through CLI or REST.
- The architecture and trade-offs are documented well enough for external review.
