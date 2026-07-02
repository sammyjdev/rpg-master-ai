# Eval baseline (Slice 1)

Recorded from `./gradlew :app:eval` on 2026-07-02, against a live local Mac stack
(bge-m3 on Ollama/Metal, Qdrant, PostgreSQL), corpus ingested from the Portuguese
D&D 5e rulebooks.

## Corpus (ingested)

| rulebook     | chunks |
|--------------|--------|
| dnd-5e-phb   | 856    |
| dnd-5e-mm    | 817    |
| dnd-5e-dmg   | 770    |
| **total**    | 2443   |

Chunking: 400 tokens / 80 overlap (ADR-007). The corpus is the **Portuguese**
translation; queries in English still retrieve correctly via bge-m3's multilingual
embedding.

## Retrieval (deterministic, `./gradlew :app:eval`)

Golden set: 45 cases (3 hand-picked seeds + 42 synthetic PT, chunk-anchored — 16
dnd-5e-phb, 15 dnd-5e-mm, 14 dnd-5e-dmg). Similarity threshold: 0.3.

| k  | mean recall@k | mean MRR |
|----|---------------|----------|
| 3  | 1.000         | 0.948    |
| 5  | 1.000         | 0.948    |
| 8  | 1.000         | 0.948    |
| 10 | 1.000         | 0.948    |

Cases are chunk-derived (synthetic), so recall is inflated in absolute terms; the
number is for relative before/after-rerank comparison, where the inflation cancels —
not absolute production recall.

### Track A: rerank on vs off (Slice 3, 2026-07-02)

The eval now routes through `RetrievalService` (shared with the query path) and
takes a `-Prerank` / `-PtopN` Gradle toggle.

| run                                        | recall@k | MRR   | result |
|---------------------------------------------|----------|-------|--------|
| rerank OFF (`:app:eval`)                     | 1.000    | 0.948 | reproduces this baseline exactly (`eval/reports/retrieval-1783017251102.md`) |
| rerank ON (`-Prerank`, topN=30 and topN=10)  | —        | —     | **BLOCKED** — TEI request times out before returning a result |

Rerank-OFF reproduces the baseline number-for-number, confirming the
`RetrievalService` refactor did not change retrieval behavior.

Rerank-ON could not be measured: `TeiRerankAdapter`'s `RestClient` has no
explicit timeout configured, so it falls back to OkHttp's default 10s read
timeout. Against real corpus-sized chunk text (not the short synthetic strings
`TeiRerankIntegrationTest` uses), TEI `cpu-1.2` running under Colima's Rosetta
emulation on this Mac does not respond within 10s even at `topN=10` (the
smallest candidate batch possible, since `RetrievalService` floors `topN` at
`topK=10` for the eval's max k-sweep) — confirmed by two failed runs
(`java.net.SocketTimeoutException` at `TeiRerankAdapter.java:43`). This is an
infra/timeout gap exposed by real-sized batches, not a defect in the eval
routing or the Gradle toggle; configuring a longer `RestClient` timeout is out
of this task's scope and left as a follow-up before Track A rerank-on numbers
can be recorded.

Recommended `k` for this corpus: recall and MRR are both flat across k=3..10 at
N=45, so the current production default of 8 buys nothing on this set — **k=3 is
sufficient for recall on this golden set**, though this is still a synthetic-set
result (see Caveats) and should be revisited once a non-synthetic eval set exists.

## Slice 2 target

This is the number reranking must beat. recall@k is already 1.0 on this set, so
the meaningful lever is **MRR (0.948 → aim for 1.0)**: the relevant page is retrieved
but not always at rank 1. A cross-encoder reranker (Slice 2) should push the relevant
page to rank 1, raising MRR without needing more recall.

## Caveats (read before trusting these numbers)

- **`recall@k` counts retrieved chunk slots, not distinct pages.** A page occupying
  multiple of the top-k chunks consumes multiple slots. This matches the metric's
  spec and is apples-to-apples for the Slice-2 comparison, but "recall@3" is not
  "top 3 distinct pages."
- **Low chunk yield from large PDFs — investigate.** phb is 184 MB but produced only
  856 chunks (~1.1x the 20 MB dmg's 770). The PDFs are image-heavy (art), so much of
  the file size is not extractable text — but this may mean parts of the rulebooks
  have no text layer and are therefore unretrievable. If the expanded golden set
  shows low recall on specific topics, suspect missing text extraction before
  blaming retrieval.
- **Modest top-hit scores (~0.58-0.60)** at threshold 0.3: the correct page ranks
  first, but not with high separation. Consistent with the low threshold admitting
  weak matches — the precision problem reranking targets.

## Answer quality (GNOMON, Track B) — snapshot, non-CI

Recorded 2026-07-02. This is an exploratory snapshot for the before/after-rerank
comparison, **not** a reproducible committed baseline and **not** a CI gate — it
depends on the GPU desktop being reachable. Track B does not affect the retrieval
numbers above.

Metric: `context_precision` (how relevant the retrieved contexts are to the
question — the retrieval-precision half that recall@k / MRR structurally miss).
`faithfulness` is reported too but is out of scope (generation-side).

Setup:
- **Generator** = `llama3.2:3b` on the Mac (Ollama). context_precision is defined
  over (question, retrieved contexts) only — not the answer — so a small fast
  generator is used deliberately (GNOMON's judge prompt does still include the
  answer, since one call scores both metrics, but both judges see the *same* answer,
  so the two-judge comparison stays fair). The production generator (`qwen2.5:7b`)
  is unchanged; modernizing it + measuring faithfulness properly is a separate
  follow-up.
- **Judges** = two independent models on the GPU desktop (RTX 4070 Ti) over
  Tailscale, for robustness.
- **Method** = `infra/scripts/gnomon_batch_two_judges.py`: generate all 45 responses
  **once**, then judge the *same* responses with each judge (8 runs each, seed 42,
  95% bootstrap CI). Generate-once keeps the two judges' inputs identical and avoids
  paying the slow local generation twice (GNOMON's own runner interleaves
  generate→judge per case and re-generates every invocation).

| judge model  | context_precision | 95% CI          | faithfulness¹ |
|--------------|-------------------|-----------------|---------------|
| llama3.1:8b  | 0.810             | 0.774 – 0.836   | 0.867         |
| gemma4:e4b   | 0.843             | 0.764 – 0.907   | 0.906         |

¹ `faithfulness` is shown for completeness only and is **not meaningful here**: it
grades whether *the answer* is grounded, but the answers came from the throwaway
`llama3.2:3b`, not the production generator. Do not read it as answer quality — a
real faithfulness measurement needs the production generator (the separate
follow-up above).

The two independent judges agree (overlapping CIs, context_precision ≈ 0.81–0.84):
the robustness signal that makes a local judge credible for the rerank comparison.
Absolute values are on a chunk-anchored synthetic set, so they matter for relative
before/after-rerank movement, not as absolute production precision.
