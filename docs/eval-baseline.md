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

| run                          | recall@k | MRR   | result |
|-------------------------------|----------|-------|--------|
| rerank OFF (`:app:eval`)       | 1.000    | 0.948 | reproduces this baseline exactly (`eval/reports/retrieval-1783017251102.md`) |
| rerank ON (`-Prerank`, topN=30, default topK=10) | 0.978 | 0.963 | `eval/reports/retrieval-1783020798176.md` |

Rerank-OFF reproduces the baseline number-for-number, confirming the
`RetrievalService` refactor did not change retrieval behavior.

**Rerank-ON: MRR improved (0.948 → 0.963, +0.015), recall@k regressed slightly
(1.000 → 0.978, flat across k=3..10)** — one of the 45 golden cases lost its
relevant chunk from the top-k after reranking. Net effect on this set: the
reranker pushes the correct chunk closer to rank 1 on the cases it keeps
right, at the cost of one case where it now ranks the relevant chunk outside
top-10 entirely. Both numbers move together across all four k values, so this
isn't a k-sensitivity artifact — it's a real, if small, precision/recall
trade-off on this synthetic set. A CI-aware read: with recall@k already at
1.000 (ceiling) pre-rerank, any reranker that reorders imperfectly will show
exactly this shape (MRR gain, recall dip) rather than "free" MRR improvement
— reranking traded a small amount of chunk-level recall for rank quality.

**Caveat: these numbers were measured against the native stand-in
(`local_reranker_server.py`), not the ONNX-backed real TEI container.** Only a
single sanity-check query was cross-checked against real TEI's `/rerank`
output (both agreed on ranking and scored the relevant chunk highest); the
full 45-case sweep was not re-run against real TEI at this scale, since it
measured too slow on this machine (see infra note below). Treat 0.978/0.963
as measured-on-equivalent-weights numbers, not TEI-container-verified at
production scale — re-validate against real TEI once running it is practical
(native amd64 hardware, or CI).

**Infra note (why this took two attempts to measure):** TEI (`cpu-1.2`) under
Colima's Rosetta amd64 emulation on this Mac measured ~12s/chunk for real
corpus-sized text — a 45-case × topN=30 sweep would take hours. Before that
measurement, a smaller topN=10 probe against the *unfixed* ~10s default
timeout also failed (expected, since 10s doesn't even cover one round-trip at
this rate) — that earlier failure was not re-run against real TEI with the
60s timeout in place, so it should not be read as re-confirmed under the
current config, only as consistent with the ~12s/chunk measurement. The
`RestClient` 60s timeout was added via `RestClientConfig` (none was configured
before — Spring's default inherited OkHttp's 10s read timeout, too tight for
cross-encoder inference regardless of the emulation issue). The numbers above
were measured instead against `infra/scripts/local_reranker_server.py`, a
native arm64 stand-in
serving the same model (`BAAI/bge-reranker-v2-m3` via `sentence-transformers`
`CrossEncoder`, CPU device — MPS/Metal measured a pathological slowdown for
this model's shapes) behind the identical `POST /rerank` contract TEI
exposes, so `TeiRerankAdapter` needed no code changes. ~11s for a 30-chunk
batch, full 45-case sweep in ~15 minutes. Production/CI on native amd64
hardware would use the real TEI container without this workaround.

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

### Track B: rerank ON (Slice 3, 2026-07-02)

Same method as above (`gnomon_batch_two_judges.py`, generate-once with
`llama3.2:3b`, judge-twice, 8 runs, seed 42, n=45), rerun with `RPG_RERANK_ENABLED=true`
(`app` booted via `RPG_RERANK_ENABLED=true SPRING_AI_OLLAMA_CHAT_MODEL=llama3.2:3b
SPRING_PROFILES_ACTIVE=local,api ./gradlew :app:bootRun`, reranker = the native
arm64 stand-in from Track A, `rpg.rerank.top-n=30` default).

| judge model  | context_precision (OFF) | context_precision (ON) | 95% CI (ON)    | Δ      |
|--------------|--------------------------|-------------------------|-----------------|--------|
| llama3.1:8b  | 0.810                    | 0.844                   | 0.829 – 0.859   | +0.034 |
| gemma4:e4b   | 0.843                    | 0.889                   | 0.818 – 0.947   | +0.046 |

Both judges move in the same direction: **rerank-ON improves context_precision**.
llama's CI barely overlaps the OFF-run CI (OFF upper bound 0.836 vs. ON lower
bound 0.829) — a real, if modest, shift. gemma's CI is wider and overlaps the
OFF-run CI substantially (OFF: 0.764–0.907), so its movement is directionally
consistent but statistically less sharp than llama's on this snapshot. Neither
judge shows a regression. This is consistent with Track A's finding (rerank
trades a small amount of chunk-level recall for better rank/relevance quality)
and is the primary success signal the plan set out to measure: **rerank-on
beats both Slice-2 baselines (0.810 llama / 0.843 gemma)**.

Same caveats as Track A rerank-on apply: measured against the native
arm64 reranker stand-in, not real TEI at this scale (see Track A above); a
non-CI, non-reproducible-on-demand snapshot (depends on the GPU desktop).
