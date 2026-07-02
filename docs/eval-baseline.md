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

## Answer quality (GNOMON, Track B) — not yet run

Blocked on the LLM: the app's chat model (qwen2.5:7b) is not hosted locally on this
machine. Track B (faithfulness + context_precision via GNOMON) will run once the LLM
is pointed at a hosted provider (OpenRouter), which needs a small `LlmPort`/Spring AI
OpenAI-compat change. Track B does not affect the retrieval numbers above.
