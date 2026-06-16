# ADR-014: LangChain4j As A Parallel Retrieval Alternative

## Status

Accepted

## Context

The existing retrieval stack is built on Spring AI: `bge-m3` embeddings via the Ollama adapter
(`EmbeddingPort`) and a hand-written Qdrant gRPC adapter (`QdrantVectorStoreAdapter` implementing
`VectorStorePort`) that talks directly to port 6334. The raw gRPC path was chosen deliberately so
that HNSW search parameters (ef=128) and payload-index filters can be tuned directly, which the
Spring AI `VectorStore` abstraction does not expose.

Before this branch, `QueryUseCase` composed those two ports inline. There was no single seam that
let the embed-plus-search step be swapped as a unit, making it hard to benchmark alternative
retrieval stacks without touching business logic.

Two questions motivated the work in this branch:

1. Could a new `RetrievalPort` (text in, `List<SourceChunk>` out) make the retrieval stack
   swappable without touching `QueryUseCase`?
2. Would LangChain4j's retrieval abstractions offer meaningful advantages over the existing
   Spring AI composition?

## Decision

Introduce a `RetrievalPort` and provide two implementations:

- `SpringAiRetrievalService` — composes the existing `EmbeddingPort` + `VectorStorePort` (the
  hand-written gRPC adapter). Annotated `@Primary`. This is the only path wired into
  `QueryUseCase` and is the production path.
- `LangChain4jRetrievalService` — uses LangChain4j's `OllamaEmbeddingModel` +
  `QdrantEmbeddingStore` against the same Ollama instance and the same Qdrant collection
  (`rpg-chunks`). It is wired as a Spring bean but is NOT injected into any production use case.

An integration test (`LangChain4jRetrievalServiceIT`) seeds one Qdrant collection with real
bge-m3 vectors and asserts that the two paths agree on more than 80% of retrieved chunk ids
(mean Jaccard similarity) across a representative query set. Both implementations surface the
Qdrant point UUID as the chunk id, so the comparison uses identical id semantics. The test acts
as a retrieval-drift guard: if a future dependency upgrade silently changes recall behaviour, the
test will fail.

Spring AI remains the primary framework. LangChain4j is an evaluated, tested, non-production
alternative kept in the codebase as a reference and drift sentinel.

## Consequences

- `QueryUseCase` is unchanged. The new `RetrievalPort` is purely additive.
- The Spring AI path retains direct gRPC access and HNSW tuning (ef=128); no retrieval
  behaviour changes in production.
- LangChain4j's `EmbeddingStore` abstraction bundles embedding and storage behind a single
  interface, which simplifies swapping stores or models but hides low-level gRPC/HNSW controls
  that this project uses intentionally.
- LangChain4j's integration modules (`langchain4j-ollama`, `langchain4j-qdrant`) are at
  `1.0.0-beta5` (managed via `langchain4j-bom`), while `langchain4j-core` is GA `1.0.0`. The
  beta modules are an API-churn and maintenance risk; the footprint is intentionally limited to
  two adapter classes and one configuration class.
- `langchain4j-qdrant` pulls `io.qdrant:client:1.13.0`, which required bumping the existing
  Qdrant client pin from 1.12.0 to 1.13.0. The gRPC channel version (1.65.1) is unchanged.
- Spring AI advantages in this project: native Spring DI, configuration, and Actuator
  integration; separate `EmbeddingPort` and `VectorStorePort` that map cleanly to hexagonal
  layers; GA status (1.0.0); and direct exposure of HNSW/payload-index tuning.
- LangChain4j advantages to monitor: broader ecosystem of pluggable stores and model adapters,
  which could reduce adapter code if portability becomes a higher priority than low-level tuning.
- LangGraph / agent orchestration is out of scope at this stage and was not evaluated.
- If the beta integration modules reach GA with a stable API and the retrieval-drift test
  continues to pass, LangChain4j could be promoted to an equal or primary path in a future ADR.
