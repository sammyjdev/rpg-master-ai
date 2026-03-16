# Gap Analysis — RPG Master AI Step 1

> Audit of the codebase against three engineering disciplines: Spec-Driven Development, Prompt Engineering, and Context Engineering.
> Scope: Step 1 (monolith) only. Results are analysis, not code fixes.

---

## A.1 — Spec-Driven Development

### What "spec-driven" means here

Every external boundary (REST API, event schema, configuration contract) should have a machine-readable specification **before** code ships. Consumers should be able to generate clients, validate payloads, and run contract tests from that spec.

### Current state

| Boundary                                                      | Spec exists? | Notes                                                                                                                                                                             |
| ------------------------------------------------------------- | ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| REST API (`/v1/chat/completions`, `/v1/ingest`, `/v1/models`) | **No**       | No OpenAPI YAML, no `springdoc-openapi` dependency. Contracts are implicit in Java records inside the controller.                                                                 |
| Domain events (future Kafka topics)                           | N/A          | No Kafka in Step 1 — but `shared-domain` event records are not yet defined.                                                                                                       |
| Qdrant collection schema                                      | **No**       | Collection name, vector dimension, payload fields, and HNSW params are scattered across `QdrantCollectionInitializer` and `QdrantVectorStoreAdapter`. No declarative schema file. |
| Database schema                                               | **Yes**      | Flyway migrations (`V1`, `V2`) serve as the spec.                                                                                                                                 |
| Configuration contract                                        | **Partial**  | `application.yml` and `application-local.yml` exist but there is no schema validation (no `@ConfigurationProperties` with `@Validated`).                                          |

### Gaps

1. **No OpenAPI spec.** The three REST endpoints (`GET /v1/models`, `POST /v1/chat/completions`, `POST /v1/ingest`) have no formal contract. A consumer cannot generate a client without reading source code. **→ RESOLVED: `springdoc-openapi` added, controllers annotated, Swagger UI live at `/swagger-ui.html`.**
2. **No error catalog.** Validation errors throw `IllegalArgumentException` which is caught by `ApiExceptionHandler` and returned as RFC 9457 `ProblemDetail` responses. However, there is no documented catalog of error types and codes.
3. **No request/response validation annotations.** `@Valid`, `@NotBlank`, `@NotNull` are absent from request records.
4. **No versioning strategy documented.** The `/v1` prefix exists but there is no decision record on how breaking changes will be handled.
5. **Qdrant schema is code-only.** The collection configuration (vector size, distance metric, HNSW params, payload indexes) should be declarative or at least documented in a spec-like format.

### Recommendations (priority order)

1. ~~Add `springdoc-openapi` and annotate controllers → auto-generated OpenAPI 3.1 spec~~ **DONE**
2. ~~Create a `@RestControllerAdvice` for RFC 9457 error responses~~ **Already exists: `ApiExceptionHandler`**
3. Add Bean Validation annotations to request records
4. Document Qdrant collection schema in `docs/adr/` or a dedicated spec file
5. Create `@ConfigurationProperties` beans with `@Validated` for Qdrant and Ollama config

---

## A.2 — Prompt Engineering

### What "prompt engineering" means here

The system prompt, user prompt template, and any few-shot examples should be versioned, testable, and decoupled from adapter code.

### Current state

| Aspect                 | Status           | Location                                                                                                                 |
| ---------------------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------ |
| System prompt          | **Externalized** | `app/src/main/resources/prompts/rag-system.st` — StringTemplate file                                                     |
| User prompt template   | **Externalized** | `app/src/main/resources/prompts/rag-user.st` — StringTemplate file                                                       |
| Prompt versioning      | **No**           | No version suffix, no A/B mechanism                                                                                      |
| Prompt used at runtime | **Diverged**     | `SpringAiLlmAdapter.buildAugmentedPrompt()` builds its own user prompt inline with a text block, bypassing `rag-user.st` |
| Language enforcement   | **Dual-layer**   | System prompt says "reply in the same language"; adapter appends `IMPORTANT: Answer in {detected_language}`              |
| Few-shot examples      | **None**         | No examples in the system prompt                                                                                         |

### Gaps

1. **Prompt template bypass.** `rag-user.st` defines `Context:\n{context}\n\nQuestion: {question}` but `SpringAiLlmAdapter` constructs its own template inline. The `.st` file is loaded into the config bean but never actually used by the adapter for user messages. This means changes to `rag-user.st` have no effect.
2. **No prompt versioning.** If the system prompt changes, there is no way to correlate answers with prompt versions. For evaluation/regression testing, prompt version should be tracked.
3. **Redundant language enforcement.** The system prompt already instructs "reply in the same language as the question." The adapter adds a second `IMPORTANT: Answer in {language}` line. This wastes tokens and could conflict if the heuristic detects the wrong language.
4. **Language detection is fragile.** `isLikelyPortuguese()` uses character scanning + stopword matching. **→ PARTIALLY RESOLVED: `é` (`\u00e9`) added to Unicode fast path; `PT_STOPWORDS` expanded from 12 to 35+ words including RPG domain vocabulary. Verified: Portuguese queries with and without accent marks now return Portuguese answers. Remaining limitation: French, Spanish, or Italian text sharing accented vowels could still produce false positives. A proper language detection library is planned for Step 2.**
5. **No evaluation harness.** There is no automated way to test prompt quality. No golden Q&A dataset, no scoring script, no regression suite.
6. **No few-shot examples.** For RPG rule citation, 1-2 examples in the system prompt would significantly improve citation format consistency (e.g., always showing "(PHB, page 231)").

### Recommendations (priority order)

1. Wire `rag-user.st` into the adapter or delete it — eliminate the dead template
2. Add a prompt version identifier (e.g., `v1.0`) to the system prompt and log it with each query
3. Remove the redundant `IMPORTANT: Answer in {language}` line now that the system prompt handles it
4. Add 1-2 few-shot citation examples to the system prompt
5. Create a golden Q&A evaluation dataset (`test/resources/golden-qa.json`) with expected answers

---

## A.3 — Context Engineering

### What "context engineering" means here

The quality and structure of context passed to the LLM — chunk size, overlap, metadata enrichment, retrieval filtering, and context window management.

### Current state

| Aspect                | Value                                          | Notes                                                     |
| --------------------- | ---------------------------------------------- | --------------------------------------------------------- |
| Chunk size            | 400 tokens (whitespace-split)                  | `ChunkConfig.defaults()`                                  |
| Overlap               | 80 tokens                                      | 20% overlap — standard for prose                          |
| Minimum chunk size    | 10 words                                       | Filters out headers, footers, page numbers                |
| Boilerplate filtering | Keyword-frequency based                        | `BOILERPLATE_KEYWORDS` set with threshold of 3            |
| Metadata prefix       | `[Source: {rulebookId} \| Page: {pageNumber}]` | Prepended to each chunk before sending to LLM             |
| Top-K                 | 8 (REST) / 5 (domain default)                  | Two different defaults — REST controller overrides domain |
| Similarity threshold  | 0.3                                            | Lowered from 0.7 → 0.5 → 0.3 for multilingual retrieval   |
| Embedding model       | bge-m3 (1024-dim)                              | Multilingual, handles EN + PT                             |
| HNSW tuning           | ef_construct=200, m=16, ef=128                 | Balanced recall/speed                                     |
| Context window budget | Not tracked                                    | No token counting before sending to LLM                   |
| Re-ranking            | None                                           | Raw cosine similarity ordering only                       |

### Gaps

1. **Top-K inconsistency.** `QueryRequest` defaults to `topK=5` but `OpenAiCompatibleController` hardcodes `DEFAULT_TOP_K=8`. A consumer using the domain record directly gets different results than one using the REST API.
2. **No context window budget.** The system sends all top-K chunks to the LLM regardless of total token count. With 8 chunks × 400 tokens = 3,200 tokens of context, plus system prompt (~200 tokens), this fits within `qwen2.5:7b`'s 32K context. But there's no guard if top-K increases or chunk size grows.
3. **No re-ranking.** Chunks are ordered by raw cosine similarity. For RPG content where keywords matter (spell names, class features), a lightweight TF-IDF or cross-encoder re-ranker would improve precision.
4. **No chunk deduplication.** Overlapping chunks from the same page can appear as separate results, inflating the context with near-duplicate text.
5. **Token counting is approximate.** `tokenCount` in `Chunk` uses whitespace split, which over-counts for languages with compound words and under-counts for CJK. For EN + PT this is acceptable but should be documented as a known limitation.
6. **No semantic chunking.** The chunker splits on fixed token windows regardless of content boundaries. A spell description that spans a chunk boundary gets split mid-sentence. Semantic chunking (split on paragraph/section boundaries) would improve retrieval quality for structured RPG content.
7. **Threshold at 0.3 is very permissive.** This was necessary due to cross-lingual embedding challenges, but it means low-relevance chunks can appear in results. Combined with no re-ranking, this can introduce noise.
8. **No query expansion.** Single-vector retrieval misses synonyms. "Fireball" won't match chunks that only say "bola de fogo" if the embedding doesn't bridge the gap. bge-m3's multilingual capability partially mitigates this, but explicit query expansion (translate query, add synonyms) would help.

### Recommendations (priority order)

1. Align top-K defaults — use 8 everywhere or make it configurable via `application.yml`
2. Add a context window token budget with truncation when exceeded
3. Implement lightweight re-ranking (TF-IDF keyword boost or reciprocal rank fusion)
4. Add chunk deduplication by page + overlap detection
5. Log context token count per query for observability
6. Document the whitespace-split approximation as a known trade-off in implementation journal

---

## Summary Matrix

| Discipline          | Maturity        | Critical Gaps                                      | Quick Wins                             |
| ------------------- | --------------- | -------------------------------------------------- | -------------------------------------- |
| Spec-Driven         | **Medium**      | No error catalog, no validation annotations        | Add Bean Validation to request records |
| Prompt Engineering  | **Medium**      | Dead template file, no versioning, no eval harness | Wire or delete `rag-user.st`           |
| Context Engineering | **Medium-High** | No re-ranking, no dedup, top-K inconsistency       | Align top-K defaults                   |

### Cross-Cutting Observations

1. **Architecture is solid.** Hexagonal ports isolate every external dependency. Sealed types enforce exhaustive handling. The foundation supports all the improvements above without structural changes.
2. **Documentation debt is low.** ADRs cover every major decision. The implementation journal captures trade-offs. This gap analysis completes the audit trail.
3. **The most impactful next action is adding OpenAPI.** It unlocks client generation, contract testing, Swagger UI for demos, and closes the biggest spec-driven gap.
