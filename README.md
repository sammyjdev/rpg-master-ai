# ⚔️ RPG Master AI

> A production-grade RAG (Retrieval-Augmented Generation) system that ingests RPG rulebooks and answers natural language questions about rules, spells, classes, and mechanics.

**Stack:** Java 21 · Spring Boot 3.3+ · Spring AI · Qdrant · PostgreSQL · Ollama

[![Build](https://img.shields.io/github/actions/workflow/status/SamDev98/rpg-master-ai/ci.yml?branch=master&label=build)](https://github.com/SamDev98/rpg-master-ai/actions)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## Current State

Phase 1 complete and signed off. The RAG loop works end-to-end via Spring Shell CLI. A PDF rulebook can be ingested and queried with natural language questions answered by a local LLM.

**Observed metrics (D&D 5e PHB):**

- Chunks stored: 856
- Ingestion time: ~63s
- Query latency: <15s (`qwen2.5:7b`)
- Qdrant collection size after full test: 4,155 points
- Embedding model: `bge-m3` (1024 dimensions, multilingual)
- Chunking: 400 tokens / 80 token overlap
- Similarity threshold: 0.3

![Demo](docs/demo.gif)

---

## Table of Contents

- [Current State](#current-state)
- [Vision](#vision)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Implementation Phases](#implementation-phases)
- [Sprint Plan](#sprint-plan)
- [Java 21 Feature Map](#java-21-feature-map)
- [Local Development](#local-development)
- [Deployment](#deployment)
- [Build in Public](#build-in-public)
- [Architectural Decisions](#architectural-decisions)
- [Risks & Mitigations](#risks--mitigations)
- [Definition of Done](#definition-of-done)

---

## Vision

RPG Master AI serves two explicit goals:

| Goal                 | Description                                                                                                             |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| **Portfolio Signal** | Demonstrates Java 21 features (Virtual Threads, Records, Pattern Matching, Structured Concurrency) in a real AI context |
| **Learning Vehicle** | Covers Spring AI, vector databases, and multi-rulebook RAG architecture incrementally                                   |

The system follows a **Build in Public** strategy: each phase ships working software AND generates a LinkedIn article.

---

## Architecture

### Current State: Step 1

The current repository implements Step 1 as a monolith with two Gradle modules:

- `shared-domain`: records and sealed domain types with zero external dependencies
- `app`: ingestion, retrieval, REST, CLI, and outbound adapters

Current runtime flow:

```
PDF file -> PDFBox extraction -> chunking -> embedding -> Qdrant + PostgreSQL
                                                  |
Question -> embedding -> Qdrant search -> RAG prompt -> local LLM -> CLI / REST answer
```

### Target Architecture

The long-term target remains a multi-service architecture split into gateway, ingestion, embedding, query, and registry services. That target is documented here as roadmap, not as current implementation.

### Step 1 Runtime Components

| Component      | Port      | Responsibility                                        | Key Tech            |
| -------------- | --------- | ----------------------------------------------------- | ------------------- |
| `app` REST API | 8082      | OpenAI-compatible query API and local ingest endpoint | Spring Boot WebFlux |
| `app` CLI      | n/a       | Interactive ingestion and query commands              | Spring Shell        |
| Qdrant         | 6333/6334 | Vector storage and similarity search                  | Qdrant              |
| PostgreSQL     | 5432      | Document metadata and lifecycle state                 | PostgreSQL + Flyway |
| Ollama         | 11434     | Local embeddings and chat generation                  | Ollama              |
| Open WebUI     | 3000      | Optional local UI for interacting with Ollama         | Open WebUI          |

### Data Storage

| Store        | Engine           | What lives there                              | Complexity             |
| ------------ | ---------------- | --------------------------------------------- | ---------------------- |
| Vector Store | Qdrant           | Text chunks + embeddings + rulebook namespace | $O(\log n)$ ANN search |
| Relational   | PostgreSQL       | Documents, ingestion jobs, audit log          | ACID, FK integrity     |
| Object Store | AWS S3 (planned) | Raw PDFs, processing artifacts                | Phase 3+               |
| Cache        | Redis (planned)  | Semantic cache (query → response)             | Phase 4+               |

---

## Tech Stack

| Layer           | Technology                | Decision Rationale                                                                                          |
| --------------- | ------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Language        | **Java 21 (LTS)**         | Virtual Threads for I/O-bound AI calls. Records for domain models. Pattern Matching for clean conditionals. |
| Framework       | **Spring Boot 3.3+**      | Native Spring AI integration. Auto-configured VT executor. Job market alignment.                            |
| AI Framework    | **Spring AI**             | Java-native model integration with adapters around chat and embeddings.                                     |
| LLM (local)     | **Ollama (`llama3.2:3b`)** | Zero-cost dev loop. Swappable via Spring AI abstraction. No API key needed locally.                         |
| Embedding Model | **Ollama (`bge-m3`)**     | Better multilingual retrieval for English and Portuguese questions.                                         |
| LLM (prod)      | **AWS Bedrock**           | AWS-native deployment. Same Spring AI interface, config-only switch.                                        |
| Vector DB       | **Qdrant**                | Payload filtering for multi-rulebook isolation. Rust performance. Spring AI first-class support.            |
| Build           | **Gradle (multi-module)** | Each service is a submodule. Shared lib as internal dependency. Faster incremental builds.                  |
| IaC             | **Terraform (planned)**   | Reserved for later production deployment phases.                                                            |

---

## Project Structure

```
rpg-master-ai/
├── settings.gradle
├── build.gradle
├── docker-compose.yml              # Local: Qdrant + Postgres + Open WebUI (Ollama on host)
│
├── shared-domain/                  # Zero-dependency module
│   └── src/main/java/.../domain/
│       ├── Chunk.java              # record Chunk(String id, String text, ...)
│       ├── Document.java
│       ├── QueryRequest.java
│       ├── QueryResult.java
│       └── IngestionResult.java    # sealed: Success | Failed | Partial
│
├── app/
│   ├── src/main/java/.../application/
│   ├── src/main/java/.../adapter/inbound/
│   ├── src/main/java/.../adapter/outbound/
│   ├── src/main/resources/prompts/
│   ├── src/test/
│   └── src/integrationTest/
│
└── docs/
        ├── ARD.md
        ├── gap-analysis.md
        ├── implementation-journal.md
        ├── linkedin-article-phase1.md
        └── adr/
```

---

## Implementation Phases

### Phase 1 — Foundation Boss `Weeks 1-2`

> Prove the RAG loop works end-to-end. No microservices, no UI. Just the loop.

**Deliverable:** Spring Boot monolith that reads a D&D 5e PDF and answers questions via CLI.

**Java 21 features introduced:** `Record` for domain models, `SequencedCollection` for chunk ordering.

| Task                            | Tech                    | Learning                                  |
| ------------------------------- | ----------------------- | ----------------------------------------- |
| Gradle multi-module setup       | Gradle 8.x              | Project structure foundation              |
| PDF ingestion                   | Apache PDFBox + Records | Text extraction, page boundaries          |
| Chunking (fixed-size + overlap) | Java Streams            | Chunk size tradeoffs (512 vs 1024 tokens) |
| Embedding generation            | Spring AI + Ollama      | What embeddings actually are              |
| Qdrant local via Docker Compose | Qdrant Java client      | Vector upsert, collection creation        |
| CLI query interface             | Spring Shell            | Retrieval + augmentation loop             |

---

### Phase 2 — API Layer & Multi-Rulebook `Weeks 3-4`

> Expose as documented REST API. Support multiple rulebooks with namespace isolation.

**Deliverable:** REST API hardening, documented contracts, multi-rulebook isolation, and stronger operational boundaries.

**Java 21 features introduced:** `Virtual Threads` for concurrent PDF processing, `Pattern Matching` for response classification, `Sealed Classes` for `IngestionResult`.

| Task                           | Tech                     | Learning                       |
| ------------------------------ | ------------------------ | ------------------------------ |
| Extract API Gateway service    | Spring Cloud Gateway     | Routing, auth filters          |
| Swagger / OpenAPI 3.1          | springdoc-openapi        | API-first documentation        |
| Rulebook namespace in Qdrant   | Qdrant payload filter    | Multi-tenant vector isolation  |
| Virtual Thread executor config | Java 21 VT + Spring      | Pinning gotchas, thread naming |
| PostgreSQL metadata schema     | Spring Data JPA + Flyway | Migration versioning           |

---

### Phase 3 — Async Ingestion Pipeline `Weeks 5-6`

> Decouple upload from processing. Large PDFs no longer block the API thread.

**Deliverable:** Kafka-based ingestion with retry, dead-letter queue, and ingestion status endpoint.

**Java 21 features introduced:** `Structured Concurrency` for coordinating embedding + storage fanout.

| Task                                   | Tech                          | Learning                                   |
| -------------------------------------- | ----------------------------- | ------------------------------------------ |
| Document Processor as separate service | Spring Kafka                  | Consumer group design, offset management   |
| Structured Concurrency fanout          | Java 21 `StructuredTaskScope` | `ShutdownOnFailure` vs `ShutdownOnSuccess` |
| Dead-letter queue + retry policy       | Kafka + `@RetryableTopic`     | Idempotency keys, at-least-once delivery   |
| Ingestion job status API               | PostgreSQL polling            | Job state machine design                   |

---

### Phase 4 — Observability & Production Hardening `Weeks 7-8`

> Make the system observable. Track latency, token usage, cache hit rate.

**Deliverable:** Grafana dashboard. Redis semantic cache. AWS deployment via Terraform.

| Task                                    | Tech                      | Learning                           |
| --------------------------------------- | ------------------------- | ---------------------------------- |
| Custom metrics: token cost, chunk count | Micrometer + Prometheus   | Custom meter registry              |
| Semantic cache with Redis               | Redis Vector + Spring AI  | Cosine similarity threshold tuning |
| Distributed tracing                     | OpenTelemetry + AWS X-Ray | Trace context propagation          |
| Terraform AWS deployment                | ECS Fargate + MSK + RDS   | IaC for AI systems                 |

---

## Sprint Plan

| Week | Sprint | Key Tasks                                                             | Done Criteria                                                 |
| ---- | ------ | --------------------------------------------------------------------- | ------------------------------------------------------------- |
| 1    | 1.1    | Gradle setup, Docker Compose, PDFBox, first chunk stored              | PDF parsed, chunks visible in Qdrant dashboard                |
| 2    | 1.2    | Spring AI embedding, similarity search, CLI query, first RAG response | "What is a Fireball?" returns correct D&D 5e answer           |
| 3    | 2.1    | REST API design, OpenAPI spec, request validation, JWT skeleton       | Swagger or equivalent contract docs for the Step 1 API        |
| 4    | 2.2    | Multi-rulebook namespace, Pathfinder 2e ingested, Virtual Threads     | D&D and Pathfinder queries correctly isolated                 |
| 5    | 3.1    | Kafka setup, document-processor service, `DocumentIngested` event     | Upload triggers async processing, status returns `PROCESSING` |
| 6    | 3.2    | Structured Concurrency fanout, DLQ, retry policy                      | Failed ingestion retried 3x then moves to DLQ                 |
| 7    | 4.1    | Micrometer metrics, Grafana dashboard, token cost tracking            | Dashboard shows latency P50/P99, token spend, cache hit rate  |
| 8    | 4.2    | Redis semantic cache, OpenTelemetry tracing, Terraform AWS deploy     | System running on ECS, public URL, README with demo GIF       |

---

## Java 21 Feature Map

No feature tourism. Every modern Java feature has a deliberate architectural home.

| Feature                     | Where Used                                                    | Why It Fits                                                      | Phase |
| --------------------------- | ------------------------------------------------------------- | ---------------------------------------------------------------- | ----- |
| `Records`                   | `Chunk`, `DocumentMetadata`, `QueryResult`, `EmbeddingVector` | Immutable domain models, no Lombok needed                        | 1     |
| `Pattern Matching (switch)` | LLM response classification, error type routing               | Exhaustive type checking on sealed response types                | 1     |
| `SequencedCollection`       | Ordered chunk list with stable first/last access              | Cleaner chunk window sliding without index arithmetic            | 1     |
| `Sealed Classes`            | `IngestionResult`: `Success \| Failed \| Partial`             | Compiler-checked domain states, exhaustive handling              | 1     |
| `Virtual Threads`           | PDF processing, embedding calls, Qdrant upserts               | I/O-bound AI calls scale to thousands with no thread pool tuning | 1     |
| `Structured Concurrency`    | Fanout: embed chunk + store metadata simultaneously           | Guaranteed cleanup on failure, readable concurrent code          | 3     |

---

## Local Development

### Prerequisites

- Java 21+
- Docker + Docker Compose
- [Ollama](https://ollama.com/download) installed and running locally

```bash
# Pull required models (one-time setup)
ollama pull bge-m3          # embedding model (~1.2 GB)
ollama pull llama3.2:3b     # chat model (~2 GB)

# Verify models are available
ollama list
```

### Quick Start

```bash
# Clone and build
git clone https://github.com/SamDev98/rpg-master-ai.git
cd rpg-master-ai

# Start infrastructure (Qdrant + PostgreSQL)
docker-compose up -d

# Build all modules
./gradlew build

# Start the CLI app
./rpgm start

# Ingest a rulebook PDF
./rpgm ingest pdfs/phb.pdf dnd-5e-phb

# Query
./rpgm query "What is the Fireball spell and what damage does it deal?" dnd-5e-phb
```

### Docker Compose Services

```yaml
# docker-compose.yml spins up:
# - Qdrant        → localhost:6333 (dashboard: localhost:6333/dashboard)
# - PostgreSQL    → localhost:5432
# - Open WebUI    → localhost:3000 (connected to local Ollama at localhost:11434)
```

### Running Tests

```bash
# Unit tests only
./gradlew test

# Integration tests (Testcontainers — requires Docker)
./gradlew integrationTest

# App module only
./gradlew :app:test
```

---

## Deployment

Production deployment is planned for a later phase. The current repository does not yet contain the Terraform or cloud runtime modules described in the long-term roadmap.

---

## Build in Public

Each phase maps to one LinkedIn article. Format: RPG analogy → technical problem → solution → code snippet → lesson.

| Phase | Article                                                                                       | Week |
| ----- | --------------------------------------------------------------------------------------------- | ---- |
| 1     | _I gave a D&D Dungeon Master a Java backend — here's what the RAG loop actually looks like_   | 2    |
| 2     | _Multi-rulebook RAG: how I used Qdrant payload filtering so D&D never bleeds into Pathfinder_ | 4    |
| 3     | _Why I added Kafka to a 4-service RAG system (hint: it's not about scale)_                    | 6    |
| 4     | _The AI demo nobody builds: tracking token cost per query in production Java_                 | 8    |

---

## Architectural Decisions

Key decisions with explicit trade-offs documented in [docs/adr/](docs/adr/):

- [ADR-001](docs/adr/ADR-001-qdrant-as-vector-store.md): Qdrant as vector store
- [ADR-002](docs/adr/ADR-002-ollama-for-local-models.md): Ollama for local models (zero API cost dev loop)
- [ADR-004](docs/adr/ADR-004-hexagonal-architecture.md): Hexagonal architecture (ports and adapters)
- [ADR-005](docs/adr/ADR-005-monolith-first-step1.md): Monolith-first for Phase 1
- [ADR-007](docs/adr/ADR-007-chunking-strategy-400-80.md): Chunking strategy — 400 tokens / 80 overlap
- [ADR-008](docs/adr/ADR-008-bge-m3-embeddings.md): bge-m3 embeddings — multilingual, 1024 dimensions
- [ADR-009](docs/adr/ADR-009-similarity-threshold-0.3.md): Similarity threshold 0.3
- [ADR-010](docs/adr/ADR-010-virtual-threads-for-io.md): Virtual Threads for I/O bound AI calls
- [ADR-011](docs/adr/ADR-011-no-lombok.md): No Lombok — Records only

---

## Risks & Mitigations

| Risk                                       | Severity | Mitigation                                                      | Fallback                                  |
| ------------------------------------------ | -------- | --------------------------------------------------------------- | ----------------------------------------- |
| Ollama too slow for demo (>10s query)      | HIGH     | Use `bge-m3` for embeddings, `llama3.2:3b` for generation       | OpenAI free tier for demo only            |
| Qdrant memory limit                        | MED      | Limit MVP to 3 rulebooks, 400-token chunks, max 10k vectors     | Qdrant Cloud free tier (1GB)              |
| Spring AI API instability (still evolving) | MED      | Pin version, isolate behind adapter interface                   | Swap adapter behind EmbeddingPort/LlmPort |
| PDF parsing quality for complex RPG tables | HIGH     | PDFBox + custom table detector. Document limitations in README. | Manual pre-processing script              |
| AWS costs during demo                      | MED      | `terraform destroy` after recording. Spot instances for ECS.    | Keep prod on Docker Compose               |

---

## Definition of Done

Portfolio-ready when a senior engineer can evaluate all of these without asking questions.

- [ ] README with architecture diagram, local setup under 5 minutes
- [x] Swagger UI live with all endpoints documented and examples
- [ ] At least 2 rulebooks ingested and queryable (D&D 5e PHB + Pathfinder 2e Core)
- [ ] Integration tests with Testcontainers (no mocks for infra)
- [ ] GitHub Actions CI: build + test on every PR (green badge on README)
- [x] ADR files for key Step 1 decisions (`docs/adr/`)
- [ ] Metrics endpoint: latency P99, token cost, cache hit rate visible
- [ ] Public demo URL or recorded GIF walkthrough in README
- [ ] 4 LinkedIn articles published, each linked to a specific commit

---

## License

MIT — see [LICENSE](LICENSE)
