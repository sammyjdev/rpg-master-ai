# Handoff — Slice 2: synthetic golden-set expansion (RPG_MASTER_AI)

Paste this into a fresh Claude Code session opened in `~/dev/rpg-master-ai`, on branch
`feat/phase-2-eval`. Resume the `superpowers:brainstorming` flow from "confirm Track B
scope → write spec → writing-plans". The design below is already agreed except the one
open decision flagged in §4.

## Objective

Raise the retrieval eval baseline from N=3 (smoke) to ~40+ deterministic cases WITHOUT
hand-authoring (user has no patience for manual T6). Use an LLM (OpenRouter) **once,
offline** to synthesize the golden set; commit it; `recall@k`/`MRR` then run forever
with no LLM. This is the reproducible baseline that a later reranking slice must beat.

## State verified in code / live (2026-07-02), not memory

- **Slice 1 is DONE, signed, pushed.** Branch `feat/phase-2-eval` @ `4cf7452` on
  `origin` (github.com:sammyjdev/rpg-master-ai). It added: `GoldenSet` loader +
  `RelevantPage`/`GoldenCase` (`app/src/test/java/com/rpgmaster/app/eval/`), metric core
  `RetrievalMetrics.recallAtK/reciprocalRank` + nested `RetrievedPage`, the
  `./gradlew :app:eval` harness (`app/src/integrationTest/java/.../RetrievalBaselineEval.java`,
  `@SpringBootTest(webEnvironment=NONE)` + `@ActiveProfiles({"local","eval"})`), GNOMON
  wiring (`eval/gnomon/`), and a production change making `/v1/chat/completions` return
  `contexts` + `usage.total_tokens` (guarded by `OpenAiCompatibleControllerTest`).
- **Corpus is INGESTED and PORTUGUESE** (PT translation). Qdrant collection `rpg-chunks`
  (1024-dim, cosine): dnd-5e-phb 856 chunks, dnd-5e-mm 817, dnd-5e-dmg 770 (total 2443).
  Page coverage 91-98% (missing pages are full-page art — normal). Text mostly clean;
  localized garbling only in dense stat blocks (bge-m3 tolerates it; recall unaffected).
- **Baseline recorded** (`docs/eval-baseline.md`, N=3): recall@k=1.0 at all k∈{3,5,8,10},
  MRR=0.778. EN queries retrieve the PT corpus fine (bge-m3 multilingual). Seed golden
  pages were corrected to PT pagination: Ankheg → dnd-5e-mm p20, Fireball/"bola de fogo"
  → dnd-5e-phb p222. topK=8 is an unvalidated tiebreak; the k-sweep exists to replace it
  with data (N=3 is too small to conclude — that is what this slice fixes).

## Environment (how to bring the eval stack back up on this Mac)

Everything runs LOCAL on the Mac (desktop 4070Ti is SSH-reachable but Docker/GUI is
blocked — passwordless account, no RDP; do not rely on it). All commands need the
sandbox disabled (gpg-agent socket, docker socket, Ollama/network) and Gradle needs it
too (daemon socket).

- **Postgres** `rpg-postgres` on host :5432 (compose service `postgres`). NOTE: host 5432
  was freed by stopping `orion-postgres` (user asked NOT to restart it — leave stopped;
  `docker start orion-postgres` to restore later).
- **Qdrant** `rpg-qdrant` — run manually (compose publishes 6333 which collides with
  `axon-qdrant`): `docker run -d --name rpg-qdrant -p 6334:6334 -p 6343:6333
  -e QDRANT__SERVICE__GRPC_PORT=6334 -v rpg_qdrant_data:/qdrant/storage
  qdrant/qdrant:v1.12.5`. App uses gRPC :6334 (`application.yml qdrant.port=6334`);
  :6343 is HTTP for inspection/scroll/search. Named volume persists the 2443 chunks.
- **Ollama on the Mac** (Metal/M1 Pro): `ollama serve` (binds 127.0.0.1:11434), `bge-m3`
  pulled (1.2 GB). `application-local.yml` already points ollama at localhost:11434.
- **App** (only needed for re-ingest or Track B, NOT for `./gradlew :app:eval`):
  `SPRING_PROFILES_ACTIVE=local,api ./gradlew :app:bootRun` (port 8082). Wrapper:
  `./rpgm start|ingest <pdf> <rulebookId>|status` (chmod +x first). Ingest allowlist =
  `${user.home}/rpg-corpus`; PDFs live at `~/rpg-corpus/{phb,mm,dmg}.pdf`. rulebookIds:
  phb→dnd-5e-phb, mm→dnd-5e-mm, dmg→dnd-5e-dmg.
- The corpus is already ingested (persistent volume), so `./gradlew :app:eval` works
  as-is against the live Qdrant + Ollama without re-ingesting or starting the app.

## Agreed design (Approach 1 — chunk-anchored)

- **Generator:** `infra/scripts/generate_golden_set.py` (repo rule: Python only under
  `infra/scripts/`). One-shot, re-runnable; output committed.
- **Source:** sample the chunks already in Qdrant (have `page_number` + `rulebook_id`),
  stratified ~13-14 per rulebook spread across page ranges; FILTER out short/garbled
  chunks (skip dense stat-block junk) to get answerable prose.
- **LLM:** OpenRouter (OpenAI-compat). Key in env `OPENROUTER_API_KEY` (NEVER commit;
  user's key is temporary). Model configurable via env `OPENROUTER_MODEL` (cheap capable
  default). Prompt: "given this D&D rulebook excerpt (PT), produce ONE natural
  player/DM question answerable from it + a concise expected answer, in PT; return JSON
  {question, expectedAnswer}".
- **Output:** append to `app/src/test/resources/golden-qa.json` (existing schema);
  `relevantPages=[{rulebookId,pageNumber}]` from the source chunk (single-rulebook — the
  loader REJECTS multi-rulebook cases). KEEP the 3 hand-verified seeds (2 EN + 1 PT for
  cross-lingual coverage). Target ~40, total ~43. Language: PT (matches corpus).
- **Validity (state explicitly in spec + eval-baseline.md):** cases are chunk-derived →
  recall is INFLATED in absolute terms. The number is for RELATIVE before/after-rerank
  comparison, where the inflation is constant and cancels. Not absolute production recall.
- **Verification:** (1) `GoldenSet.load` validates the generated set; (2) `./gradlew
  :app:eval` records the new N≈43 baseline into `eval/reports/` and update
  `docs/eval-baseline.md`; (3) a per-case assert in the script before writing. No test
  framework for the script — it is tooling.
- **Out of scope:** reranking (the slice AFTER this), garbling cleanup (YAGNI until eval
  shows it matters).

## §4 — THE ONE OPEN DECISION: add Track B (GNOMON) or not?

User said "add Track B if it gives a big gain." My verdict handed over for confirmation:
- `context_precision` = a REAL, complementary gain: it measures retrieval PRECISION/noise
  (how much junk is in the top-k under threshold 0.3) — the half of reranking's effect
  that recall@k/MRR structurally cannot see. On a chunk-anchored set (which inflates
  recall/MRR), context_precision is also the LESS circular, more discriminating rerank
  signal. `faithfulness` is marginal (generation-side, not rerank).
- Costs: temp key → Track B is a NON-REPRODUCIBLE snapshot (Track A stays the committed
  reproducible baseline); and B ~doubles implementation surface — it needs an OpenRouter
  `LlmPort` adapter (Spring AI OpenAI → OpenRouter base-url, profile-selected) so the app
  generates answers, then run GNOMON (judge also via OpenRouter) against the synthetic
  questions.
- Recommendation: INCLUDE Track B for `context_precision`, scoped honestly as an
  exploratory snapshot; A is the committed baseline. **First action in the new chat:
  confirm A-only vs A+B with the user, then write the spec accordingly.**

## Skills / flow to resume

`superpowers:brainstorming` is mid-flow (scope + Approach 1 already chosen). Next:
confirm §4 → present final design section if B is added → write spec to
`docs/superpowers/specs/2026-07-02-slice2-synthetic-eval-set-design.md` + commit →
spec self-review → user review → `superpowers:writing-plans`. Then execute via
`superpowers:subagent-driven-development` (Slice 1 pattern: implementer=haiku for
transcription / sonnet for judgment, reviewer=sonnet, final review=opus).

## Git / signing gotchas (this machine)

- Commits: gpg-agent socket is blocked in the sandbox → subagents commit UNSIGNED
  (`git commit --no-gpg-sign`), then re-sign the range at the end OUTSIDE the sandbox:
  `git rebase --autostash <last-signed> --exec "git commit --amend --no-edit -S"` (user
  runs it via `!` for the pinentry passphrase; last signed tip is `4cf7452`).
- Subagents must `git add` ONLY their named paths — the repo has unrelated uncommitted
  changes (CLAUDE.md, .axon/, docs/agents/) that must never be staged.
- Sandbox: docker/gradle/gpg/ollama/network commands all need
  `dangerouslyDisableSandbox: true` (socket/network denials otherwise).

## Do not duplicate — reference

`.superpowers/sdd/progress.md` (full session ledger), `docs/eval-baseline.md`,
`docs/superpowers/specs/2026-07-01-rpgmaster-eval-baseline-design.md` +
`docs/superpowers/plans/2026-07-01-eval-baseline-slice1.md` (Slice 1),
`eval/gnomon/README.md` + `config.toml` (GNOMON already wired to the extended endpoint).
