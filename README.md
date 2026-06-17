# ⚔️ RPG Master AI

> A production-grade RAG (Retrieval-Augmented Generation) system that ingests RPG rulebooks and answers natural language questions about rules, spells, classes, and mechanics.

**Stack:** Java 21 · Spring Boot 3.3+ · Spring AI · Qdrant · PostgreSQL · Ollama

[![Build](https://img.shields.io/github/actions/workflow/status/sammyjdev/rpg-master-ai/ci.yml?branch=master&label=build)](https://github.com/sammyjdev/rpg-master-ai/actions)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## Current State

Phase 1 complete and signed off. The RAG loop works end-to-end via Spring Shell CLI. A PDF rulebook can be ingested and queried with natural language questions answered by a local LLM.

**Phase 2 — Evaluation & Observability — in progress.**
Observability half is shipped on branch `feat/phase-2-eval`:

- Prompt versioning (`# version: vX.Y` header in every `.st` template) propagated to every query
- Per-query JSON audit log on the dedicated `rpg.query.audit` logger
- Micrometer meters for latency, token usage, cost (USD), ingestion throughput, embedding latency
- Prometheus + Grafana via `docker-compose`; an 8-panel dashboard provisioned as code under `infra/observability/`
- See [docs/observability.md](docs/observability.md) and [ADR-012](docs/adr/ADR-012-observability-contract.md)

Eval harness (golden Q&A dataset, retrieval + answer metrics, baseline report) is the next deliverable.

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

| Goal                 | Description                                                                                                                                 |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **AI Engineer Signal** | Production-grade RAG with measurable quality: golden Q&A eval, prompt versioning, observability of tokens/latency/cost, multi-model swap |
| **Java 21 Showcase** | Records, Sealed types, Pattern Matching, Virtual Threads, Structured Concurrency — every feature with a deliberate architectural home       |
| **Zero-Cost Portfolio** | Runs free locally (Ollama). Free cloud demo via NVIDIA NIM. No recurring spend required to clone, run, or evaluate                       |

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
| Prometheus     | 9090      | Scrapes `/actuator/prometheus` from the app           | Prometheus          |
| Grafana        | 3001      | RAG dashboard (RPG Master AI / RAG Operations)        | Grafana             |

### Data Storage

| Store        | Engine           | What lives there                              | Complexity             |
| ------------ | ---------------- | --------------------------------------------- | ---------------------- |
| Vector Store | Qdrant           | Text chunks + embeddings + rulebook namespace | $O(\log n)$ ANN search |
| Relational   | PostgreSQL       | Documents, ingestion jobs, audit log          | ACID, FK integrity     |
| Object Store | AWS S3 (future)  | Raw PDFs, processing artifacts                | Phase 5 — prod validation |
| Cache        | Redis (future)   | Semantic cache (query → response)             | Phase 5 — prod validation |

---

## Tech Stack

| Layer           | Technology                | Decision Rationale                                                                                          |
| --------------- | ------------------------- | ----------------------------------------------------------------------------------------------------------- |
| Language        | **Java 21 (LTS)**         | Virtual Threads for I/O-bound AI calls. Records for domain models. Pattern Matching for clean conditionals. |
| Framework       | **Spring Boot 3.3+**      | Native Spring AI integration. Auto-configured VT executor. Job market alignment.                            |
| AI Framework    | **Spring AI**             | Java-native model integration with adapters around chat and embeddings.                                     |
| LLM (local)     | **Ollama (`qwen2.5:7b`)** | Zero-cost dev loop. Swappable via Spring AI abstraction. No API key needed locally.                         |
| Embedding Model | **Ollama (`bge-m3`)**     | Better multilingual retrieval for English and Portuguese questions.                                         |
| LLM (cloud-free) | **NVIDIA NIM**           | Free-tier catalog (Llama 3.3, Nemotron, Mixtral, …). OpenAI-compatible. Enables multi-model eval comparison. Phase 3. |
| LLM (prod, future) | **AWS Bedrock**       | Same `LlmPort` adapter swap. Phase 5 — validates the hexagonal abstraction against a paid enterprise provider.        |
| Vector DB       | **Qdrant**                | Payload filtering for multi-rulebook isolation. Rust performance. Spring AI first-class support.            |
| Build           | **Gradle (multi-module)** | Each service is a submodule. Shared lib as internal dependency. Faster incremental builds.                  |
| IaC             | **Terraform (future)**    | Reserved for Phase 5 production validation. Not required for portfolio scope.                               |

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

> Roadmap rewritten to optimize for **AI Engineer positioning** and **zero recurring cost**. Kafka, Terraform, and Bedrock are deferred to Phase 5 as production-validation extensions — not required for the portfolio story.

### Phase 1 — Foundation ✅ DONE `Weeks 1-2`

> Prove the RAG loop works end-to-end. No microservices, no UI. Just the loop.

**Deliverable:** Spring Boot monolith that reads a D&D 5e PDF and answers questions via CLI + OpenAI-compatible REST.

**Java 21 features introduced:** `Record` for domain models, `Sealed` types for `IngestionResult`, `Pattern Matching`, `Virtual Threads` for I/O.

| Task                            | Tech                    | Status |
| ------------------------------- | ----------------------- | ------ |
| Gradle multi-module setup       | Gradle 8.x              | ✅ |
| PDF ingestion                   | Apache PDFBox + Records | ✅ |
| Chunking (400 tokens / 80 overlap) | Java Streams         | ✅ |
| Embeddings (`bge-m3`, 1024-dim) | Spring AI + Ollama      | ✅ |
| Qdrant local via Docker Compose | Qdrant Java client      | ✅ |
| CLI + OpenAI-compatible REST    | Spring Shell + WebFlux  | ✅ |
| Swagger / OpenAPI 3.1           | springdoc-openapi       | ✅ |
| 11 ADRs + gap-analysis          | docs/adr/               | ✅ |

---

### Phase 2 — Evaluation & Observability `Weeks 3-4`

> Stop guessing. Every RAG change from here on is justified by a number in the eval report.

**Deliverable:** Golden Q&A harness, prompt versioning, Prometheus + Grafana, gap-analysis quick wins closed.

**Why this phase exists:** the single biggest gap in most RAG portfolios is the absence of evaluation. This is the highest-ROI work for an AI Engineer positioning.

| Task                                              | Tech                          | Learning                                          |
| ------------------------------------------------- | ----------------------------- | ------------------------------------------------- |
| Gap-analysis quick wins (dead `rag-user.st`, topK align, Bean Validation) | — | Closing the obvious holes first                   |
| Golden Q&A dataset (~30 questions, EN + PT)       | JSON fixture                  | Eval-set design for domain-specific RAG           |
| `./gradlew eval` harness                          | Java + LLM-as-judge or keyword match | recall@k, citation accuracy, faithfulness scoring |
| Prompt versioning + per-query logging             | `.st` header + SLF4J          | Reproducibility, regression tracking              |
| Micrometer custom meters                          | Spring Actuator + Prometheus  | Token / latency / cost metrics                    |
| Grafana dashboard committed as code               | Grafana JSON + compose        | Observability-as-code                             |
| Eval badges in README                             | shields.io                    | Public proof, not narrative                       |

---

### Phase 3 — Cloud-Free LLM Swap + Retrieval Quality `Weeks 5-6`

> Swap providers via the hexagonal port — Ollama vs NVIDIA NIM — and let the eval harness pick the winner. Improve retrieval based on measured gaps.

**Deliverable:** NIM adapter, comparative multi-model eval report, re-ranking + dedup + context budget. Still zero recurring cost — NIM free tier handles demo traffic.

**Java 21 features introduced:** `Structured Concurrency` for parallel multi-model eval fanout.

| Task                                | Tech                                      | Learning                                  |
| ----------------------------------- | ----------------------------------------- | ----------------------------------------- |
| NVIDIA NIM adapter                  | Spring AI OpenAI-compatible client        | Hexagonal port pays off — config-only swap |
| Multi-model comparative eval        | Golden Q&A × {Ollama, NIM models}         | Benchmarking LLMs for a specific domain   |
| Reciprocal Rank Fusion re-ranker    | Pure Java                                 | Beyond raw cosine similarity              |
| Chunk dedup by page overlap         | Java Streams                              | Cleaner context, fewer tokens             |
| Context token budget                | Tokenizer estimate + truncation           | Window management                         |
| Eval report diff: before vs after   | Markdown                                  | Provable improvement                      |

---

### Phase 4 — Multi-Rulebook + Distribution `Weeks 7-8`

> Prove namespace isolation with a second rulebook. Ship the whole thing as one `docker run`.

**Deliverable:** Pathfinder 2e ingested, isolation integration test, multi-arch image on GHCR, README inverted for 5-minute onboarding.

| Task                                              | Tech                          | Learning                                  |
| ------------------------------------------------- | ----------------------------- | ----------------------------------------- |
| Pathfinder 2e ingestion                           | Existing pipeline             | Multi-tenant proof                        |
| Namespace isolation integration test              | Testcontainers + Qdrant       | Payload filtering correctness             |
| GHCR Docker image (multi-arch)                    | GitHub Actions buildx         | Distribution                              |
| README inverted: "Try in 5 minutes" first         | —                             | Documentation UX                          |
| Cost & Hardware Footprint section                 | Real measurements             | Honest portfolio signal                   |
| Demo GIF #2: both rulebooks isolated              | VHS                           | Show, don't tell                          |

---

### Phase 5 — Production Validation `Future, time-permitting`

> Only after the portfolio story is closed. Validates the hexagonal architecture against real enterprise concerns.

**Deliverable:** Bedrock adapter (paid), Terraform deploy, optional Kafka async ingestion. Each item is justified independently — none are part of the core portfolio scope.

| Task                                | Tech                                      | When to do it                                |
| ----------------------------------- | ----------------------------------------- | -------------------------------------------- |
| AWS Bedrock LLM + embeddings adapter | Spring AI + AWS SDK                       | Proves `LlmPort` survives a paid provider    |
| Terraform AWS deploy (ECS + RDS + Qdrant Cloud) | IaC                          | Only if targeting AWS-focused role           |
| Async ingestion via Kafka + DLQ     | Spring Kafka + `StructuredTaskScope`      | Only when ingestion volume justifies it      |
| Redis semantic cache                | Redis Vector + Spring AI                  | Only when query volume justifies it          |
| Distributed tracing                 | OpenTelemetry                             | Only when running across multiple services   |

---

## Sprint Plan

| Week | Sprint | Key Tasks                                                                          | Done Criteria                                                            |
| ---- | ------ | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| 1    | 1.1 ✅ | Gradle setup, Docker Compose, PDFBox, first chunk stored                           | PDF parsed, chunks visible in Qdrant dashboard                           |
| 2    | 1.2 ✅ | Spring AI embedding, similarity search, CLI query, first RAG response, OpenAPI    | "What is a Fireball?" returns correct D&D 5e answer; Swagger live        |
| 3    | 2.1    | Gap-analysis quick wins + golden Q&A dataset + eval harness skeleton               | `./gradlew eval` runs and produces `eval/reports/*.md`                   |
| 4    | 2.2    | Micrometer + Prometheus + Grafana + prompt versioning + eval badges                | Dashboard shows P50/P99, token usage; README shows live eval badges      |
| 5    | 3.1    | NVIDIA NIM adapter + comparative multi-model eval                                  | Eval report compares Ollama vs ≥2 NIM models on the same golden set     |
| 6    | 3.2    | RRF re-ranking, chunk dedup, context budget — all gated by eval                    | Eval recall@k and faithfulness measurably improve vs Phase 2 baseline    |
| 7    | 4.1    | Pathfinder 2e ingestion + namespace isolation integration test                     | Cross-rulebook query test passes; D&D answers don't leak Pathfinder      |
| 8    | 4.2    | GHCR multi-arch image, README inversion, Cost & Hardware Footprint, Demo GIF #2    | `docker run ghcr.io/sammyjdev/rpg-master-ai` works in <5 min, end-to-end |

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
ollama pull qwen2.5:7b      # chat model (~4.7 GB)

# Verify models are available
ollama list
```

### Quick Start

```bash
# Clone and build
git clone https://github.com/sammyjdev/rpg-master-ai.git
cd rpg-master-ai

# Start infrastructure (Qdrant + PostgreSQL)
docker-compose up -d

# Build all modules
./gradlew build

# Start the CLI app
./rpgm start

# Ingest a rulebook PDF
# PDFs must live under the ingest allowlist root (~/rpg-corpus by default; see ADR-013).
./rpgm ingest ~/rpg-corpus/phb.pdf dnd-5e-phb

# Query
./rpgm ask "What is the Fireball spell and what damage does it deal?"
```

### Docker Compose Services

```yaml
# docker-compose.yml spins up:
# - Qdrant        → localhost:6333 (dashboard: localhost:6333/dashboard)
# - PostgreSQL    → localhost:5432
# - Open WebUI    → localhost:3000 (connected to local Ollama at localhost:11434)
# - Prometheus    → localhost:9090 (scrapes the app on host:8082)
# - Grafana       → localhost:3001 (anonymous Viewer; admin/admin to edit)
```

The Grafana instance auto-provisions the Prometheus datasource and any JSON
dashboards committed under `infra/observability/grafana/dashboards/`. After
`docker-compose up -d`, open <http://localhost:3001> and navigate to the
"RPG Master AI" folder.

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

| Phase | Article                                                                                                       | Week |
| ----- | ------------------------------------------------------------------------------------------------------------- | ---- |
| 1     | _I gave a D&D Dungeon Master a Java backend — here's what the RAG loop actually looks like_                   | 2    |
| 2     | _How I stopped guessing if my RAG works — building an eval harness for a D&D rulebook bot in Java_            | 4    |
| 3     | _Same Java code, three LLMs: how the hexagonal port survived Ollama, NVIDIA NIM, and a comparative benchmark_ | 6    |
| 4     | _Multi-tenant RAG with Qdrant payload filtering — and why your second rulebook is the real test_              | 8    |
| 5     | _Took my RAG from Ollama to AWS Bedrock without changing one line of business logic_ — `future`               | —    |

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
- [ADR-012](docs/adr/ADR-012-observability-contract.md): Observability contract — metric names, audit log shape, prompt versioning

---

## Risks & Mitigations

| Risk                                       | Severity | Mitigation                                                      | Fallback                                  |
| ------------------------------------------ | -------- | --------------------------------------------------------------- | ----------------------------------------- |
| Ollama too slow for demo (>10s query)      | HIGH     | `bge-m3` embeddings + `qwen2.5:7b` chat, both on Ollama         | NVIDIA NIM free tier (Phase 3) for demos  |
| Qdrant memory limit                        | MED      | Limit MVP to 3 rulebooks, 400-token chunks, max 10k vectors     | Qdrant Cloud free tier (1GB)              |
| Spring AI API instability (still evolving) | MED      | Pin version, isolate behind adapter interface                   | Swap adapter behind `EmbeddingPort` / `LlmPort` |
| PDF parsing quality for complex RPG tables | HIGH     | PDFBox + boilerplate filter. Document limitations in README.    | Manual pre-processing script              |
| Eval set bias / overfitting to golden Q&A  | MED      | Keep golden set small, curated, diverse (EN + PT, multi-domain) | Rotate held-out questions per phase       |
| NIM free-tier rate limits                  | LOW      | Cache results during eval; throttle to provider limits          | Fall back to Ollama for the same eval     |

---

## Definition of Done

Portfolio-ready when a senior AI engineer can evaluate all of these without asking questions.

- [x] README with architecture diagram and local setup
- [ ] README inverted: "Try in 5 minutes" section at the top, runs from a single `docker run`
- [x] Swagger UI live with all endpoints documented and examples
- [x] Integration tests with Testcontainers (no mocks for infra)
- [x] GitHub Actions CI: build + test on every PR (green badge on README)
- [x] ADR files for key Step 1 decisions (`docs/adr/`)
- [ ] Golden Q&A eval harness committed; latest report in `eval/reports/`
- [ ] README shows live eval badges (recall@k, faithfulness, P99)
- [ ] Metrics endpoint: latency P99, token usage per query, cost estimate visible in Grafana
- [ ] Comparative eval across ≥3 LLM configurations (Ollama + NIM models)
- [ ] At least 2 rulebooks ingested and queryable (D&D 5e PHB + Pathfinder 2e Core)
- [ ] Multi-arch Docker image published on GHCR
- [ ] Cost & Hardware Footprint section with real numbers
- [ ] 4 LinkedIn articles published, each linked to a specific commit

---

## License

MIT — see [LICENSE](LICENSE)
