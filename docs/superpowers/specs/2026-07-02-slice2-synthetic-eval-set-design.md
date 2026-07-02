# Design — Synthetic golden-set expansion + GNOMON precision snapshot (Slice 2)

Date: 2026-07-02
Status: proposed
Branch: feat/phase-2-eval

## Context

Slice 1 (`2026-07-01-rpgmaster-eval-baseline-design.md`, `4cf7452`) built the
measurement: a `GoldenSet` loader, `RetrievalMetrics` (recall@k / MRR), and the
`./gradlew :app:eval` harness (`@SpringBootTest(webEnvironment=NONE)`,
profiles `local,eval`). It recorded a baseline at **N=3** — a smoke set, too small
to conclude anything (recall@k=1.0 at every k, MRR=0.778). The `topK=8` production
value is still an unvalidated tiebreak; the k-sweep exists to replace it with data,
but N=3 cannot.

A later slice wants cross-encoder reranking. Reranking must be proven against a
baseline, and the baseline must be large enough to discriminate. This slice raises
the golden set to ~40+ cases **without hand-authoring** (the user has no patience
for manual T6), by using an LLM **once, offline** to synthesize cases from the
corpus already in Qdrant. The generated set is committed; recall@k / MRR then run
forever with no LLM.

This spec covers **Slice 2 only**: grow the golden set (Track A) and add a
retrieval-precision snapshot via the already-wired GNOMON (Track B). Reranking is a
**later** slice, validated against the baseline this slice produces.

## Scope

**In:**
- Track A — synthetic golden-set generator + expanded committed set + new N≈43 baseline.
- Track B — a `context_precision` snapshot via the existing local GNOMON wiring.

**Out (YAGNI until eval shows it matters):**
- Reranking (the slice after this).
- `faithfulness` (generation-side, marginal for a rerank signal).
- Corpus garbling cleanup (bge-m3 tolerates it; recall unaffected).
- Any cloud LLM adapter or change to the external GNOMON repo.

## Track A — synthetic golden set (Approach 1: chunk-anchored)

### Generator

`infra/scripts/generate_golden_set.py` (repo rule: Python only under
`infra/scripts/`). One-shot, re-runnable; output is committed.

### Source sampling

Sample the chunks already in Qdrant (`rpg-chunks`, gRPC :6334 / HTTP :6343). Each
chunk carries `page_number` + `rulebook_id` in its payload. Strategy:

- Stratified ~13-14 chunks **per rulebook** (dnd-5e-phb, dnd-5e-mm, dnd-5e-dmg),
  spread across page ranges so cases are not clustered.
- **Filter out** short / garbled chunks (dense stat-block junk) — keep answerable
  prose. Heuristics: minimum character length, minimum alphabetic-ratio; skip
  chunks dominated by numbers/symbols. Thresholds tuned by inspecting rejects.
- Target ~40 generated cases; total ~43 with the 3 kept seeds.

### LLM synthesis

- Provider: OpenRouter (OpenAI-compatible). Key in env `OPENROUTER_API_KEY`
  (NEVER commit; the user's key is temporary). Model via env `OPENROUTER_MODEL`
  with a cheap-capable default.
- Prompt (PT): "given this D&D rulebook excerpt (PT), produce ONE natural
  player/DM question answerable from it + a concise expected answer, in PT; return
  JSON `{question, expectedAnswer}`".
- Per-case assert **in the script** before writing: non-empty `question` and
  `expectedAnswer`, valid JSON, `question` differs from the raw excerpt. Drop and
  re-sample on failure so the target count is met. This LLM use is Track A's ONLY
  LLM dependency and it is offline/one-shot.

### Output

- Append to `app/src/test/resources/golden-qa.json` (existing schema, verified):
  `{id, question, expectedAnswer, relevantPages:[{rulebookId, pageNumber}]}`.
- `relevantPages` = the single source chunk's `{rulebookId, pageNumber}`. The
  loader **rejects multi-rulebook cases**, so exactly one page entry, one rulebook.
- **Keep the 3 hand-verified seeds** (`gq-001`/`gq-002` EN, `gq-003` PT) for
  cross-lingual coverage. Generated cases are PT (matches the corpus).
- Stable ids: continue the `gq-NNN` sequence.

### Validity (stated in spec + `docs/eval-baseline.md`)

Cases are chunk-derived → the source page is guaranteed to contain the answer →
**recall is inflated in absolute terms**. The number is for **relative**
before/after-rerank comparison, where the inflation is a constant that cancels. It
is NOT absolute production recall. `docs/eval-baseline.md` must say this in one
sentence next to the number.

### Verification

1. `GoldenSet.load` validates the generated set (single-rulebook guard, schema).
2. `./gradlew :app:eval` records the new N≈43 baseline into `eval/reports/` and
   updates `docs/eval-baseline.md` (k-sweep now has enough N to inform `topK`).
3. Per-case assert in the script before writing (above). No test framework for the
   script — it is tooling.

## Track B — `context_precision` snapshot (local, distinct judge)

### Why

recall@k / MRR are recall-side: they cannot see how much irrelevant junk sits in
the top-k under threshold 0.3. `context_precision` measures exactly that — the half
of reranking's effect the Track A metrics structurally miss, and the less circular
rerank signal on a chunk-anchored set.

### What is already wired (verified, not memory)

- `eval/gnomon/config.toml`: `[target]` = the live app's OpenAI-compat endpoint
  (`/v1/chat/completions`, model `all-rulebooks`); the app generates answers via
  its own configured chat model. `[judge]` = Ollama, local.
- `GnomonDatasetExport.java` (`app/src/integrationTest/.../eval/`) exports
  `eval/gnomon/dataset.json` from the golden set.
- The production `/v1/chat/completions` already returns top-level `contexts` +
  `usage.total_tokens` (Slice 1, guarded by `OpenAiCompatibleControllerTest`), so
  GNOMON's `OpenAICompatTarget` runs against it as-is.
- GNOMON's judge `provider` is `Literal["ollama", "stub"]` (`run_config.py:31`) —
  **no cloud judge without modifying the external GNOMON repo**, which is out of
  scope.

### Design

- **Compute split (Mac + GPU desktop):** the RPG corpus in Qdrant was ingested with
  the **Mac** `bge-m3`, so **embeddings + generation stay on the Mac** (query
  embeddings must match the index; re-ingest is out of scope). Only the **judge** —
  the heavy part (`judge_runs=8 × N≈43 ≈ 344` calls, and it embeds nothing) — runs
  on the GPU desktop over Tailscale at `http://100.78.123.92:11434` (RTX 4070 Ti,
  12 GB). See memory `[[desktop-model-engine]]` for access details.
- **Generator** = the app's Mac Ollama chat model (`qwen2.5:7b`,
  `application-local.yml`). No adapter, no new profile — the target is the live
  endpoint, unchanged.
- **Judge** = a **second, distinct** model on the desktop, via `config.toml`
  `[judge] base_url = "http://100.78.123.92:11434"`. Distinct-from-generator kills
  the self-preference bias the config already flags (generator == judge inflates
  scores).
- **Two-judge robustness run:** run GNOMON **twice** — once with
  `judge = llama3.1:8b`, once with `judge = gemma4:e4b` — and report both
  `context_precision` + CI. Agreement across two independent judges is the
  robustness evidence that makes a local judge credible for the rerank signal;
  divergence flags judge-sensitivity to investigate before trusting the metric.
- **Metric** = `context_precision` only. `faithfulness` gate stays record-only /
  out of the reported result.
- **Reproducibility:** no temp key, all local/LAN → Track B is reproducible on this
  Mac + desktop, unlike the handoff's stale OpenRouter framing. Still scoped as a
  **snapshot**, not a CI gate: it depends on the desktop being up and two Ollama
  models being pulled there, and is not wired into `./gradlew` CI.

### Authority note (recorded decision)

For the benchmark's actual job — relative before/after-rerank comparison — a
**consistent** judge is what matters, not a frontier one; systematic judge bias
cancels between the two runs, exactly like the recall inflation. A cloud/frontier
judge would only buy **absolute** credibility, which this benchmark does not claim
(chunk-anchored, exploratory). If an absolute frontier-judged headline is ever
wanted for the portfolio, that is a separate, well-scoped task ("add an
openai_compat judge provider to GNOMON") and must not be bundled here.

### Verification

1. Expand golden set (Track A) → re-run `GnomonDatasetExport` → `dataset.json`
   reflects N≈43.
2. On the desktop: `bge-m3` present; pull `llama3.1:8b` (`gemma4:e4b` already there).
   In `config.toml` set `[judge] base_url = "http://100.78.123.92:11434"`.
3. Start the app on the Mac (`local,api`). Run `gnomon --config config.toml` from
   `eval/gnomon/` **twice**, overriding `[judge] model` to `llama3.1:8b` then
   `gemma4:e4b`.
4. Record both `context_precision` + 95% CI into `docs/eval-baseline.md` under a
   clearly-labelled **Track B (snapshot, non-CI)** section, noting generator model,
   both judge models, the desktop, and the date.

## Files touched

- `infra/scripts/generate_golden_set.py` (new).
- `app/src/test/resources/golden-qa.json` (append generated cases; keep seeds).
- `eval/gnomon/config.toml` (`[judge] base_url` → desktop; `[judge] model` swapped
  per robustness run).
- `docs/eval-baseline.md` (new N≈43 Track A baseline + Track B snapshot section).
- `eval/reports/` (new Track A report from `./gradlew :app:eval`).

## Non-goals reaffirmed

No reranking, no `faithfulness`, no garbling cleanup, no change to the external
`~/dev/gnomon-eval` repo, and no cloud LLM adapter **in the app / GNOMON path**
(OpenRouter is used only by the offline Track A generator script, never by the
runtime or the eval harness).
