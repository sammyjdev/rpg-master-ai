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

Golden set: 3 cases (Ankheg → mm p20, Fireball → phb p222, "bola de fogo" → phb p222).
Similarity threshold: 0.3.

| k  | mean recall@k | mean MRR |
|----|---------------|----------|
| 3  | 1.000         | 0.778    |
| 5  | 1.000         | 0.778    |
| 8  | 1.000         | 0.778    |
| 10 | 1.000         | 0.778    |

Recommended `k` for this corpus: **cannot be concluded yet** — recall saturates at
k=3 and MRR is flat across the sweep, so the current production default of 8 buys no
extra recall on this set, but N=3 is far too small to change the default. Revisit
after the golden set is expanded (see Caveats).

## Slice 2 target

This is the number reranking must beat. recall@k is already 1.0 on this tiny set, so
the meaningful lever is **MRR (0.778 → aim for 1.0)**: the relevant page is retrieved
but not always at rank 1. A cross-encoder reranker (Slice 2) should push the relevant
page to rank 1, raising MRR without needing more recall.

## Caveats (read before trusting these numbers)

- **N=3, not statistical.** This is a smoke-scale baseline that validates the
  pipeline end-to-end (ingest → embed → Qdrant → recall@k/MRR → report), not a
  representative measurement. Expanding to ~20-30 cases (T6) is required before any
  real conclusion about k, threshold, or reranking gain.
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
