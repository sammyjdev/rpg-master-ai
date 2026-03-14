# RPG Master AI — Project Instructions for GitHub Copilot

> This file is the authoritative context for all AI agents working on this codebase.
> Read it entirely before touching any file. Every architectural decision here was deliberate.

---

## Project Identity

**Name:** RPG Master AI
**Type:** Production-grade RAG (Retrieval-Augmented Generation) system
**Domain:** RPG rulebook ingestion + natural language query
**Owner:** Sammy — Senior Java Engineer (6 years, Java/Spring ecosystem)
**Goals:** Portfolio signal for AI Engineering roles + learning vehicle for Java 21 + Spring AI

---

## Non-Negotiable Constraints

| Constraint                 | Rule                                                                                    |
| -------------------------- | --------------------------------------------------------------------------------------- |
| **Language**               | Java 21 only. No Kotlin, no Scala. Python only in `infra/scripts/` for tooling.         |
| **Java Version**           | Minimum Java 21 LTS. Use modern features intentionally (see Feature Map).               |
| **Framework**              | Spring Boot 3.3+. No Quarkus, no Micronaut.                                             |
| **Build**                  | Gradle multi-module. Never Maven.                                                       |
| **No Lombok**              | Records replace DTOs. No `@Data`, `@Builder`, `@Getter`.                                |
| **No ORM magic**           | Spring Data JPA for simple queries only. Complex queries = raw SQL via `JdbcClient`.    |
| **No feature tourism**     | Java 21 features only where they genuinely improve the code (see Feature Map).          |
| **Hexagonal Architecture** | Business logic never imports Spring/Kafka/Qdrant directly. Always via ports.            |
| **Test containers**        | Integration tests use real infra (Testcontainers). No mocking of Qdrant/Kafka/Postgres. |

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                      api-gateway :8080                   │
│          Spring Cloud Gateway + JWT + Swagger            │
└──────────────┬──────────────────────────┬───────────────┘
               │                          │
    ┌──────────▼──────────┐    ┌──────────▼──────────┐
    │  document-processor  │    │    query-service     │
    │       :8081          │    │       :8083          │
    │  PDFBox + Kafka      │    │  RAG + SSE + LLM     │
    └──────────┬──────────┘    └──────────┬──────────┘
               │                          │
    ┌──────────▼──────────┐    ┌──────────▼──────────┐
    │  embedding-service   │    │  rulebook-registry   │
    │       :8082          │    │       :8084          │
    │  Spring AI adapter   │    │  Metadata + Flyway   │
    └──────────┬──────────┘    └─────────────────────┘
               │
    ┌──────────▼──────────────────────────┐
    │            Infrastructure            │
    │  Qdrant | PostgreSQL | Kafka | Redis │
    │         S3 | Ollama / Bedrock        │
    └──────────────────────────────────────┘
```

### Two Pipelines (Never Mix Them)

**Ingestion (async):** `POST /rulebooks/{id}/documents` → S3 → Kafka `document.ingested` → document-processor → embedding-service → Qdrant + PostgreSQL

**Query (sync):** `POST /query` → api-gateway → query-service → embedding-service → Qdrant ANN → LLM → SSE stream

---

## Current Scope: Increment 1 (MVP CLI)

This is a **monolith** — two modules only: `shared-domain` and `app`.

- No Kafka, no Redis, no S3, no Spring Cloud Gateway
- Ingestion is synchronous
- Interface is Spring Shell CLI only
- Storage: Filesystem (local PDF) + Qdrant (Docker) + PostgreSQL (Docker)
- LLM + Embeddings: Ollama (`nomic-embed-text` + `llama3.2:3b`) via Docker

---

## Module Map (Increment 1)

```
rpg-master-ai/
├── shared-domain/          ← ZERO external dependencies. Records + Sealed classes only.
└── app/                    ← Monolith: ingestion + query, hexagonal architecture
    ├── application/port/   ← Port interfaces (EmbeddingPort, VectorStorePort, etc.)
    ├── application/        ← Use cases (IngestionUseCase, QueryUseCase)
    ├── adapter/inbound/    ← Spring Shell commands
    ├── adapter/outbound/   ← Qdrant, PostgreSQL, PDFBox, Ollama adapters
    └── config/             ← Spring @Configuration classes
```

---

## Domain Model (shared-domain)

```java
// All domain types are Records. Immutable by default.

record Chunk(
    String id,               // UUID
    String text,             // Raw text content
    int tokenCount,          // Approximate token count
    int pageNumber,          // Source page in PDF
    String documentId,       // FK to Document
    String rulebookId,       // Namespace key for Qdrant payload filter
    Map<String, String> metadata
) {}

record Document(
    String id,
    String filename,
    String rulebookId,
    IngestionStatus status,  // PENDING | PROCESSING | COMPLETED | FAILED
    Instant uploadedAt,
    int totalChunks
) {}

record QueryRequest(
    String question,
    String rulebookId,       // null = search all rulebooks
    int topK,                // default: 5
    float similarityThreshold // default: 0.7
) {}

record QueryResult(
    String answer,
    List<SourceChunk> sources,
    int tokensUsed,
    long latencyMs
) {}

// Sealed for exhaustive pattern matching
sealed interface IngestionResult permits
    IngestionResult.Success,
    IngestionResult.Failed,
    IngestionResult.Partial {

    record Success(String documentId, int chunksStored) implements IngestionResult {}
    record Failed(String documentId, String reason, Throwable cause) implements IngestionResult {}
    record Partial(String documentId, int chunksStored, int chunksFailed) implements IngestionResult {}
}
```

---

## Java 21 Feature Map

| Feature                     | Canonical Use                                                | Anti-Pattern to Avoid                                    |
| --------------------------- | ------------------------------------------------------------ | -------------------------------------------------------- |
| `Record`                    | All domain types in `shared-domain`                          | Using `@Data` classes as domain models                   |
| `Sealed + Pattern Matching` | `IngestionResult` handling in document-processor             | `instanceof` chains, unchecked casts                     |
| `Virtual Threads`           | `@Bean ThreadFactory virtualThreadFactory()` in all services | Creating `ExecutorService` with fixed pool for I/O tasks |
| `Structured Concurrency`    | `embed + store` fanout in embedding-service                  | `CompletableFuture.allOf()` for coordinated async tasks  |
| `SequencedCollection`       | Chunk list with ordered window access                        | `List.get(0)` / `List.get(list.size()-1)`                |
| `Text Blocks`               | SQL queries, system prompts for LLM                          | String concatenation for multi-line SQL                  |

---

## Package Structure (Hexagonal)

```
com.rpgmaster.app/
├── domain/          ← Records, sealed types, pure business logic
├── application/     ← Use cases, orchestration, port interfaces
├── adapter/
│   ├── inbound/     ← Spring Shell commands (Increment 1), REST controllers (Increment 2+)
│   └── outbound/    ← Qdrant, PostgreSQL, PDFBox, Ollama adapters
└── config/          ← Spring @Configuration classes
```

---

## Code Style Rules

1. **No `@Autowired` on fields.** Constructor injection only. Always `final`.
2. **All public methods in ports have Javadoc.** Not optional.
3. **Logging:** SLF4J only. No `System.out.println`. Structured JSON logs in prod.
4. **No `synchronized` keyword** in Virtual Thread hot paths — use `ReentrantLock`.
5. **Switch on sealed types must be exhaustive** — no `default` branch.
6. **Flyway manages schema** — never `spring.jpa.ddl-auto=create`.

---

## Database Schema Conventions

- Snake_case for all table and column names
- `id` is always `UUID` with `DEFAULT gen_random_uuid()`
- `created_at` and `updated_at` on every table
- No `SERIAL` / auto-increment — UUIDs only
- Indexes on all foreign keys and query-hot columns

---

## Testing Standards

```
Unit Test:        Pure Java, no Spring context. Test domain logic in isolation.
Integration Test: @SpringBootTest + Testcontainers. Tests full service slice.
Contract Test:    WireMock stubs for external services (LLM, Qdrant HTTP).
```

- `shared-domain`: 100% coverage
- Business logic (`application/`): 80%+
- Adapters: integration tests only, no unit mocks

---

## Spring Profiles

| Profile | Use                | LLM                      | Vector DB               |
| ------- | ------------------ | ------------------------ | ----------------------- |
| `local` | Docker Compose dev | Ollama (localhost:11434) | Qdrant (localhost:6333) |
| `test`  | Testcontainers     | WireMock stub            | Testcontainers Qdrant   |
| `prod`  | AWS ECS (Phase 3+) | AWS Bedrock              | Qdrant Cloud            |
