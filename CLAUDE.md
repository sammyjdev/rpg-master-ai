# CLAUDE.md — RPG Master AI Project Brain

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

These are hard rules. No agent should violate them without explicit human approval.

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

## Module Map

```
rpg-master-ai/
├── shared-domain/          ← ZERO external dependencies. Records + Sealed classes only.
├── shared-test/            ← Testcontainers configs, fixture builders, WireMock stubs.
├── api-gateway/            ← Routing, auth, rate limiting, Swagger aggregation.
├── document-processor/     ← PDF parsing, chunking, Kafka producer.
├── embedding-service/      ← Text → vector. Adapters for Ollama and OpenAI/Bedrock.
├── query-service/          ← RAG orchestration, Qdrant retrieval, LLM call, SSE.
├── rulebook-registry/      ← Rulebook CRUD, namespace management, Flyway migrations.
└── infra/terraform/        ← AWS: ECS, MSK, RDS, S3, CloudWatch.
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

## Port Interfaces (Hexagonal Architecture)

Every external system is behind a port. Agents must respect this boundary.

```java
// embedding-service ports
public interface EmbeddingPort {
    List<Float> embed(String text);
    List<List<Float>> embedBatch(List<String> texts);
}

// query-service ports
public interface VectorStorePort {
    void upsert(String rulebookId, List<ChunkVector> chunks);
    List<ScoredChunk> search(String rulebookId, List<Float> queryVector, int topK, float threshold);
}

public interface LlmPort {
    Flux<String> generateStream(String systemPrompt, String userPrompt, List<String> context);
}

// document-processor ports
public interface DocumentStoragePort {
    String upload(byte[] bytes, String filename);  // returns S3 key
    InputStream download(String key);
}

public interface ChunkingPort {
    List<Chunk> chunk(String text, String documentId, String rulebookId, ChunkConfig config);
}
```

---

## Java 21 Feature Map

Agents must use these features in exactly these contexts. No substitutions.

| Feature                     | Canonical Use                                                | Anti-Pattern to Avoid                                    |
| --------------------------- | ------------------------------------------------------------ | -------------------------------------------------------- |
| `Record`                    | All domain types in `shared-domain`                          | Using `@Data` classes as domain models                   |
| `Sealed + Pattern Matching` | `IngestionResult` handling in document-processor             | `instanceof` chains, unchecked casts                     |
| `Virtual Threads`           | `@Bean ThreadFactory virtualThreadFactory()` in all services | Creating `ExecutorService` with fixed pool for I/O tasks |
| `Structured Concurrency`    | `embed + store` fanout in embedding-service                  | `CompletableFuture.allOf()` for coordinated async tasks  |
| `SequencedCollection`       | Chunk list with ordered window access                        | `List.get(0)` / `List.get(list.size()-1)`                |
| `Text Blocks`               | SQL queries, system prompts for LLM                          | String concatenation for multi-line SQL                  |

---

## Configuration Standards

### Spring Profiles

| Profile | Use                | LLM                      | Vector DB               |
| ------- | ------------------ | ------------------------ | ----------------------- |
| `local` | Docker Compose dev | Ollama (localhost:11434) | Qdrant (localhost:6333) |
| `test`  | Testcontainers     | WireMock stub            | Testcontainers Qdrant   |
| `prod`  | AWS ECS            | AWS Bedrock              | Qdrant Cloud            |

### application.yml Conventions

```yaml
# Every service must declare:
spring:
  application:
    name: rpg-master-{service-name}
  threads:
    virtual:
      enabled: true # Java 21 Virtual Threads — always on

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    tags:
      service: ${spring.application.name}
```

---

## Kafka Topics

| Topic               | Producer           | Consumer           | Schema                  |
| ------------------- | ------------------ | ------------------ | ----------------------- |
| `document.ingested` | api-gateway        | document-processor | `DocumentIngestedEvent` |
| `document.chunked`  | document-processor | embedding-service  | `DocumentChunkedEvent`  |
| `document.embedded` | embedding-service  | rulebook-registry  | `DocumentEmbeddedEvent` |
| `document.dlq`      | any (on failure)   | ops/alerting       | `DeadLetterEvent`       |

All events are JSON. No Avro in MVP. Schema lives in `shared-domain/events/`.

---

## Database Schema Conventions

Managed by Flyway. All migrations in `src/main/resources/db/migration/`.

```
V1__create_rulebooks.sql
V2__create_documents.sql
V3__create_ingestion_jobs.sql
```

Rules:

- Snake_case for all table and column names
- `id` column is always `UUID` with `DEFAULT gen_random_uuid()`
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

Coverage requirements:

- `shared-domain`: 100% (it's just Records, there's no excuse)
- Service business logic (`application/` package): 80%+
- Adapters: integration tests only, no unit mocks

---

## Code Style Rules

1. **Package structure per service** (Hexagonal):

   ```
   com.rpgmaster.{service}/
   ├── domain/          ← Records, sealed types, pure business logic
   ├── application/     ← Use cases, orchestration, port interfaces
   ├── adapter/
   │   ├── inbound/     ← REST controllers, Kafka consumers
   │   └── outbound/    ← Qdrant, PostgreSQL, S3, LLM adapters
   └── config/          ← Spring @Configuration classes
   ```

2. **No `@Autowired` on fields.** Constructor injection only. Always `final`.

3. **Controllers return `ResponseEntity<>` always.** Never raw objects.

4. **Error responses follow RFC 9457** (Problem Details for HTTP APIs).

5. **All public methods in ports have Javadoc.** Not optional.

6. **Logging:** SLF4J only. No `System.out.println`. Structured JSON logs in prod.

---

## Observability Contracts

Every service must emit these metrics via Micrometer:

| Metric                           | Type    | Labels                                    |
| -------------------------------- | ------- | ----------------------------------------- |
| `rpg.query.latency`              | Timer   | `rulebook_id`, `model`                    |
| `rpg.query.tokens_used`          | Counter | `rulebook_id`, `direction` (input/output) |
| `rpg.ingestion.chunks_processed` | Counter | `rulebook_id`, `status`                   |
| `rpg.cache.hit`                  | Counter | `type` (semantic/exact)                   |
| `rpg.embedding.latency`          | Timer   | `model`, `batch_size`                     |

---

## Current Phase

**Active:** Phase 1 — Foundation
**Next Milestone:** CLI query returns correct D&D 5e answer for "What is a Fireball?"
**Blocked by:** Nothing. Start with `docker-compose up` and `shared-domain` Records.

---

## Agents Index

Contextual instructions live in `.github/instructions/` and are auto-injected by VS Code when editing matching files.
Full reference material for all increments is preserved in `AGENT-*.md` at the root.

| File                                                 | `applyTo`                            | Domain                                                               |
| ---------------------------------------------------- | ------------------------------------ | -------------------------------------------------------------------- |
| `.github/instructions/java21.instructions.md`        | `**/*.java`                          | Java 21 conventions: Records, Virtual Threads, pattern matching      |
| `.github/instructions/qdrant.instructions.md`        | `**/*Qdrant*.java`                   | Qdrant collection design, search patterns, HNSW tuning               |
| `.github/instructions/rag-architect.instructions.md` | `**/application/**/*.java`           | RAG pipeline: chunking strategy, prompt templates, retrieval quality |
| `.github/instructions/spring-ai.instructions.md`     | `**/adapter/outbound/SpringAi*.java` | Spring AI: Ollama config, embedding adapter, LLM adapter             |
| `.github/instructions/kafka.instructions.md`         | `**/*Kafka*.java`                    | Kafka: topic config, consumer/producer patterns, event schemas       |
| `.github/instructions/infra.instructions.md`         | `docker-compose*.yml`                | Docker Compose: service naming, volume conventions                   |
| `.github/prompts/linkedin-article.prompt.md`         | manual                               | LinkedIn article structure, RPG analogies, publishing checklist      |
