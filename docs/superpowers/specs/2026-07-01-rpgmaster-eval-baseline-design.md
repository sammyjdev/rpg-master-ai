# Design — Eval baseline for retrieval quality (Slice 1)

Date: 2026-07-01
Status: proposed
Branch: feat/phase-2-eval

## Context

The RAG pipeline is vector-only: `query → bge-m3 embedding → Qdrant search (topK=8,
similarity-threshold=0.3) → stuff into prompt`. There is no reranking, no formal
evaluation, and no golden Q&A set (`docs/gap-analysis.md`, still valid). The
similarity threshold was lowered empirically `0.7 → 0.5 → 0.3` for multilingual
retrieval (ADR-009) — a value calibrated without measurement.

A follow-up wants to add cross-encoder reranking. This design deliberately comes
*first*: adding a reranker without a baseline is faith, not engineering. You cannot
prove a retrieval change helped without a way to measure retrieval.

This spec covers **Slice 1 only**: build the measurement. Reranking is **Slice 2**,
a separate spec that will be validated against the baseline this slice produces.

### Decomposition

- **Slice 1 (this spec):** golden Q&A set + baseline eval (deterministic retrieval
  metric + GNOMON answer-quality guardrail). No change to the retrieval pipeline.
- **Slice 2 (future spec):** cross-encoder reranking as a second stage, measured
  against this baseline. Open questions Q1 (model), Q2 (hexagonal placement),
  Q3 (candidate pool), Q4 (hybrid BM25) belong there. See "Slice 2 anchors" below.

## Goals

- A versioned golden Q&A dataset that encodes both expected answers and expected
  source pages.
- A deterministic, cheap retrieval metric (`recall@k`, `MRR`) that isolates the
  retrieval stage — the primary lever a reranking change moves — swept across
  `k ∈ {3, 5, 8, 10}` so the retrieval depth is measured for this corpus rather than
  guessed (the current `topK=8` is an unvalidated tiebreak).
- GNOMON wired as an end-to-end answer-quality guardrail (`faithfulness`,
  `context_precision`, each with a confidence interval, gated in CI).
- A recorded baseline: the current numbers, so Slice 2 has something to beat.

## Non-goals

- No reranking, no hybrid/BM25 retrieval, no chunking changes. All deferred to
  Slice 2 or later.
- No new inbound HTTP endpoint (the deterministic metric calls the port directly;
  the guardrail reuses the existing OpenAI-compatible controller).

## The golden set — shared artifact

- Location: `app/src/test/resources/golden-qa.json` (the path `gap-analysis.md` §74
  already proposes).
- Per-case schema:

  ```json
  {
    "id": "gq-001",
    "question": "What is an Ankheg's armor class?",
    "expected_answer": "AC 14 (natural armor), 11 while prone.",
    "relevant_pages": [{ "rulebook_id": "dnd-5e-mm", "page_number": 21 }]
  }
  ```

- `relevant_pages` feeds Track A (deterministic retrieval). `expected_answer` feeds
  Track B (GNOMON judge). One file serves both tracks.
- Initial size: ~20-30 cases, honest and small. Coverage targets the failure modes
  the domain suggests: exact-match rule/monster lookups (`Ankheg`, `AC 14`), spell
  blocks that span chunk boundaries, and PT/EN multilingual queries. Small N is
  acceptable — GNOMON reports confidence intervals, so uncertainty is explicit, not
  hidden.

## Track A — deterministic retrieval metric (primary)

- Lives as an in-repo Java test under `app/src/test/`, calling `VectorStorePort.search`
  **directly** — no HTTP, no LLM, no answer generation.
- For each golden `question`: run search, take the top-k returned chunks, compare
  their `(rulebookId, pageNumber)` against `relevant_pages`.
- Metrics: `recall@k` (did a relevant page make the top-k) and `MRR` (how high did
  the first relevant page rank). MRR is the rank-sensitive metric reranking should
  move even when recall@k is already saturated.
- **`k` is swept, not fixed.** The current `topK=8` has no empirical basis — it was
  an arbitrary tiebreak between a domain default of 5 and a hardcoded 8 (see
  gap-analysis §102), and the literature gives no universal optimal k (the noise
  onset is corpus- and model-specific; reported anywhere from ~5 to ~250 chunks
  depending on dataset). So Track A evaluates `recall@k` / `MRR` across
  `k ∈ {3, 5, 8, 10}`. `k` becomes an *output* of the eval — the recommended
  retrieval depth for this corpus — not a guessed input. This directly answers
  "is 8 too many?" with a number.
- Note the interaction with `similarity-threshold=0.3`: a low threshold admits weak
  matches (cosine 0.3 is barely related), so noise here is a *precision* problem, not
  a context-length one (8×400 = 3,200 tokens is well under any degradation
  threshold). The k-sweep exposes where added depth stops adding relevant pages and
  starts adding noise.
- Rationale for calling the port directly: the OpenAI-compatible response does not
  carry sources, but the application layer already exposes them
  (`QueryUseCase` → `result.sources()`). Calling the port avoids a new endpoint, CLI
  output parsing, and an HTTP hop — smallest diff, most robust. It is a port
  consumer like any other, consistent with the hexagonal architecture (ADR-004).
- Runs natively under `mvn test` / CI. Can gate on a `recall@k` floor once the
  baseline is recorded.

## Track B — answer-quality guardrail (GNOMON)

- GNOMON (external, `~/dev/gnomon-eval`) points its `[target]` block at
  `http://localhost:PORT/v1` — the existing `OpenAiCompatibleController`
  (`/v1/chat/completions`, `/v1/models`, model `all-rulebooks`). No RPG_MASTER code
  change.
- Metrics: `faithfulness` + `context_precision`, each with a 95% CI, gate on a
  threshold (exit 1 below floor) so it works as a CI regression gate.
- Golden set is converted to GNOMON's dataset format using `expected_answer`.
- Catches what Track A cannot: prompt/generation regressions where the right page
  was retrieved but the answer is still wrong.

## Data flow

```
golden-qa.json ──┬─→ Track A (Java test) ─→ VectorStorePort.search ─→ recall@k, MRR
                 │
                 └─→ GNOMON dataset ─→ /v1/chat/completions ─→ faithfulness, context_precision (±CI)
```

## Error handling & edge cases

- Golden set is validated at load (Track A): missing fields, empty `relevant_pages`,
  or unknown `rulebook_id` fail the test loudly rather than silently scoring 0.
- A `relevant_pages` page that does not exist in the ingested corpus is a dataset
  bug, not a retrieval miss — the loader asserts each referenced page is ingestible
  (or the test is skipped with a clear message if the corpus is absent in CI).
- GNOMON gate failure (metric below floor) exits non-zero; Track A threshold failure
  fails the Maven build. Both are intended CI gates, off by default until the
  baseline is recorded (first run records numbers, does not gate).

## Testing

- Track A *is* the test. Its own correctness (recall/MRR math) gets a tiny unit
  check with a hand-built fixture (known retrieved set vs known relevant set →
  known recall/MRR), so a metric bug can't silently pass the baseline.
- Track B is exercised by running GNOMON against a locally running instance;
  documented as a runbook step, not a unit test.

## Slice 2 anchors (deferred, recorded so nothing is lost)

- **Q3 — candidate pool:** decided in principle. The pre-rerank candidate pool must
  be **explicit and derived from `topK`** (e.g. `poolSize = topK * factor`), never
  inherited from the previous stage nor set to an unbounded budget. This is the
  glyph-kg anti-pattern (`_CANDIDATE_BUDGET = 50_000` "to pass all candidates
  through") — avoid it explicitly.
- **Q1 — model, Q2 — hexagonal placement, Q4 — hybrid BM25:** deferred to the Slice 2
  spec, to be decided against the recorded baseline. Note: `gap-analysis.md` already
  flags "no query expansion" with the exact `Fireball` vs `bola de fogo` example.
  If the baseline shows exact-term matching (rule/monster names) is the dominant
  failure, hybrid BM25+vector (via RRF) may matter more than reranking, and the
  attack order flips. Measuring first is what lets that call be made on numbers.

## Success criteria

- `golden-qa.json` exists with ~20-30 validated cases.
- Track A runs under `mvn test`, prints `recall@k` and `MRR` across
  `k ∈ {3, 5, 8, 10}`, backed by a unit check on the metric math.
- GNOMON runs against a live instance and reports `faithfulness` + `context_precision`
  with CIs.
- Baseline numbers are recorded (in this repo, e.g. a short `docs/eval-baseline.md`)
  so Slice 2 has a target to beat, including the recommended `k` for this corpus
  chosen from the sweep (highest recall/MRR before noise plateaus).
