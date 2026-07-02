# Design — Cross-encoder reranking (Slice 3)

Date: 2026-07-02
Status: proposed
Branch: feat/phase-2-eval

## Context

Slice 1 built the retrieval eval; Slice 2 grew it to a committed baseline and added a
precision snapshot (`docs/eval-baseline.md`, `docs/eval-results-matrix.md`):

- **Track A** (N=45, chunk-anchored synthetic, threshold 0.3): recall@k = 1.000 at all
  k, MRR = 0.948.
- **Track B** (two-judge snapshot): context_precision = 0.810 (llama3.1:8b) / 0.843
  (gemma4:e4b).

The pipeline is vector-only: `embed → Qdrant ANN (topK=8, threshold 0.3) → LLM`. There
is no reranking. This slice adds a cross-encoder reranker between retrieval and the LLM,
and measures it against the Slice-2 baseline it must beat.

**Headroom (from the matrix):** on the chunk-anchored set recall is saturated (1.0) and
MRR is near-ceiling (0.948) — little room there. **context_precision (0.81–0.84) is the
metric with real headroom and is the primary success target.** Because a reranker only
reorders what ANN already returned, the design retrieves **topN ≫ topK** (default N=30,
K=8) so the reranker can *promote* chunks the ANN ranked 9–30 into the final top-8 —
that is where reranking can move recall@8 / MRR / precision even on this set.

This spec covers **Slice 3 only**: add reranking + measure it. Tuning the reranker
model, multi-stage reranking, and hard-negative golden-set expansion are out of scope.

## Scope

**In:**
- A `RerankPort` + a TEI-backed cross-encoder adapter (bge-reranker-v2-m3).
- A shared `RetrievalService` (search + optional rerank) used by both the query path
  and the eval harness, so eval measures exactly what production does.
- Config + profile toggle for clean rerank on/off A/B.
- TEI as a local Docker service + a Testcontainers integration test.
- Eval: Track A (recall@k/MRR on vs off) + Track B (context_precision with rerank on),
  compared against the Slice-2 baseline.

**Out (YAGNI):**
- Reranker model tuning / fine-tuning.
- Multi-stage / cascade reranking.
- Hard-negative golden-set expansion (considered and deferred; the topN≫topK lever is
  used instead to give the reranker room on the existing set).
- Changing cross-rulebook behaviour.

## Architecture (hexagonal)

### Port

```java
// app/src/main/java/com/rpgmaster/app/application/port/RerankPort.java
public interface RerankPort {
    /**
     * Reorders candidates by cross-encoder relevance to the query and returns the
     * top-K. The returned SourceChunks carry the reranker score in {@code score}.
     */
    List<SourceChunk> rerank(String query, List<SourceChunk> candidates, int topK);
}
```

### Adapter

`app/src/main/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapter.java` — implements
`RerankPort`. POSTs to TEI `/rerank` with `{query, texts[]}` (texts = candidate chunk
texts, in order), receives `[{index, score}, …]` sorted by score, remaps indices back to
the original `SourceChunk`s (preserving chunkId/page/rulebookId), overwrites `score` with
the reranker score, truncates to topK. Constructor-injected TEI base-url + timeout. On TEI
error, fail loud (the query path surfaces a clear error; eval fails visibly) — no silent
fallback to the ANN order, so a broken reranker is never mistaken for a passing one.

### Shared retrieval path (targeted refactor)

Extract `RetrievalService` in `application/`:

```java
List<SourceChunk> retrieve(String rulebookId, String question, float threshold);
```

It embeds, calls `vectorStorePort.search(rulebookId, vec, topN, threshold)`, and — when
rerank is enabled — calls `rerankPort.rerank(question, candidates, topK)`; otherwise it
truncates the ANN result to topK. `topN`, `topK`, and the enabled flag come from config.
`QueryUseCase` and the eval harness both call `RetrievalService`, so they never drift.

## Data flow (query path, rerank enabled)

```
embed(question)
  → vectorStore.search(rulebookId, vec, topN=30, threshold=0.3)   // candidates
  → rerank(question, candidates, topK=8)                          // reorder + truncate
  → buildContextWithMetadata
  → llm.generate
```

With rerank disabled the middle step is a truncate-to-topK of the ANN order — the exact
current behaviour, so "off" reproduces the Slice-2 baseline.

## Configuration

```yaml
# application.yml
rag:
  retrieval:
    top-k: 8
    similarity-threshold: 0.3
  rerank:
    enabled: false          # off by default until proven
    top-n: 30               # candidates fetched before reranking (>= top-k)
    model: BAAI/bge-reranker-v2-m3
    base-url: http://localhost:8090   # TEI
```

Profiles: `local` (rerank optional, off by default), a new `rerank` profile (or
`rag.rerank.enabled=true` override) that turns it on for the A/B eval run. `top-n` is
swept in the eval to pick a value, mirroring how Slice 1 swept k.

## Infrastructure — TEI

TEI (`text-embeddings-inference`, CPU image) serving bge-reranker-v2-m3, run on the Mac
via colima (same Docker pattern as Qdrant/Postgres), published on :8090. A
`docker-compose` service + a Testcontainers `GenericContainer` for the integration test.
CPU rerank of ~30 candidates/query is ~1–3s — acceptable for dev/eval. (Desktop GPU was
rejected: the Windows box's Docker is blocked and Ollama does not serve rerankers.)

## Eval integration

- **Track A:** run `./gradlew :app:eval` twice — rerank off (must reproduce recall@k=1.0,
  MRR=0.948) then rerank on — and record both, plus a `top-n` sweep. The harness calls
  `RetrievalService`, so "on" exercises the real rerank path. Note: the eval sweeps
  k∈{3,5,8,10}, so it requests the reranked list at `topK = maxK (=10)` and truncates per
  k (exactly as it does today with the ANN result) — it does not truncate at the
  production topK=8. Only the query path uses topK=8.
- **Track B:** re-run `infra/scripts/gnomon_batch_two_judges.py` with the app booted
  rerank-on; compare context_precision (llama + gemma) against 0.81–0.84. Same
  generate-once/two-judge method, same generator (llama3.2:3b), for apples-to-apples.
- Record results in `docs/eval-baseline.md` + `docs/eval-results-matrix.md` as a
  rerank-on vs rerank-off comparison.

## Success criteria

1. **Primary:** context_precision (rerank on) > Slice-2 baseline on both judges, or a
   clear, CI-aware statement if it does not move (a negative result is a valid,
   honestly-reported outcome — the eval exists to tell the truth, not to confirm).
2. **Secondary:** MRR (rerank on, topN=30) ≥ 0.948; recall@8 not regressed.
3. Rerank-off must exactly reproduce the Slice-2 baseline (proves the toggle is clean).

Validity carries over: numbers are relative (chunk-anchored synthetic set), not absolute
production quality.

## Testing

- **Unit:** `TeiRerankAdapter` mapping — given a stubbed TEI `/rerank` response
  (WireMock), the adapter reorders and truncates the right `SourceChunk`s and carries the
  rerank score. Cover: fewer candidates than topK, TEI error → surfaced.
- **Integration:** Testcontainers TEI (bge-reranker-v2-m3) — a real rerank call reorders a
  small candidate set as expected.
- **Eval:** the before/after run above is the end-to-end verification.
- `shared-test` gets a TEI Testcontainer config alongside the existing Qdrant/Kafka ones.

## Files touched

- `app/.../application/port/RerankPort.java` (new)
- `app/.../adapter/outbound/TeiRerankAdapter.java` (new)
- `app/.../application/RetrievalService.java` (new) + `QueryUseCase` (use it)
- `app/.../config/` — rerank config props + adapter wiring
- `app/src/main/resources/application.yml` (+ `application-rerank.yml` if a profile is used)
- `app/.../integrationTest/.../RetrievalBaselineEval.java` (route through `RetrievalService`)
- `shared-test/` — TEI Testcontainer
- `docker-compose*.yml` — TEI service
- `docs/eval-baseline.md`, `docs/eval-results-matrix.md` — rerank on/off results

## Non-goals reaffirmed

No reranker tuning, no multi-stage rerank, no golden-set hard-negative expansion, no
cloud reranker, no cross-rulebook change.
