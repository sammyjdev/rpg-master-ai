# Handoff — Slice 3: cross-encoder reranking (RPG_MASTER_AI)

Paste into a fresh Claude Code session in `~/dev/rpg-master-ai`, branch
`feat/phase-2-eval`. Design + plan are done and committed; this session EXECUTES the plan.

## Start here

1. Read the plan: `docs/superpowers/plans/2026-07-02-slice3-reranker.md` — it is the
   task-by-task source of truth (6 tasks, TDD, full code). Spec:
   `docs/superpowers/specs/2026-07-02-slice3-reranker-design.md`. Baseline to beat:
   `docs/eval-baseline.md` + `docs/eval-results-matrix.md`.
2. Execute via `superpowers:subagent-driven-development` (Slice 2 pattern: implementer
   haiku for transcription / sonnet for judgment, reviewer sonnet, final review opus).
   Ledger: `.superpowers/sdd/progress.md`.

## What Slice 3 builds (one line)

`RerankPort` + `TeiRerankAdapter` (bge-reranker-v2-m3 via TEI) + shared `RetrievalService`
(`search topN=30 → rerank → topK=8`, toggle `rpg.rerank.enabled`), then eval on-vs-off.
Primary success = **context_precision beats 0.810 (llama) / 0.843 (gemma)**; recall/MRR
secondary (already near-ceiling on the chunk-anchored set). A negative result reported
honestly is a valid outcome.

## State verified (2026-07-02), not memory

- Slice 2 DONE, signed, on branch. Golden set N=45, Track A recall@k=1.0/MRR=0.948,
  Track B context_precision llama=0.810 / gemma=0.843 (two-judge snapshot).
- Config prefix is **`rpg`** (`rpg.retrieval`, new `rpg.rerank`) — NOT `rag`.
- `SourceChunk(chunkId, text, pageNumber, score, rulebookId)`. Ports auto-wired as
  `@Component`; `@ConfigurationPropertiesScan("com.rpgmaster.app.config")` auto-registers
  props records. No RestClient in main yet (adapter introduces it).

## Environment / gotchas (carried from Slice 2)

- **Re-sign FIRST** (SHAs changed by the last rebase): last signed tip = `121d439`
  (the plan commit). Wait — after Slice 2 re-sign the tip was `8b2395c`; the plan/spec
  commit `121d439` is UNSIGNED. Re-sign the range the user last signed onward. Confirm
  the current signed tip with `git log --format="%h %G? %s" | head`, then re-sign from
  the last `G` outside the sandbox:
  `git rebase --autostash <last-signed> --exec "git commit --amend --no-edit -S"` (user
  runs via `!` for pinentry). NOTE: re-signing changes SHAs, so do it before dispatching
  implementers, or after all Slice-3 commits (the plan's Finalization re-signs from the
  Slice-3 base).
- **Sandbox:** docker / gradle / network / ollama commands need `dangerouslyDisableSandbox: true`.
- **App boot:** `--args` is eaten by spring-shell → pass overrides as ENV vars.
  Working boot: `SPRING_PROFILES_ACTIVE=local,api SPRING_AI_OLLAMA_CHAT_MODEL=... RPG_RERANK_ENABLED=... ./gradlew :app:bootRun` (port **8082**; 8080 is Colima's SSH mux, do not use). Launch via `nohup … &` (tool-tracked background tasks get killed ~mid-run; nohup survives).
- **Infra up:** Qdrant `rpg-qdrant` (gRPC :6334 / HTTP :6343, 2443 chunks), Postgres
  `rpg-postgres` :5432, Mac Ollama :11434 (`bge-m3`, `llama3.2:3b`, `qwen2.5:7b`). TEI is
  NEW: `docker compose up -d tei-reranker` → :8090 (first start downloads ~2GB model).
- **Desktop judges** (Track B): `samde@100.78.123.92`, Ollama at
  `http://100.78.123.92:11434` (`llama3.1:8b`, `gemma4:e4b`). See memory
  `desktop-model-engine`.
- **Track B is slow:** `infra/scripts/gnomon_batch_two_judges.py` generates once
  (llama3.2:3b) then judges twice; ~30 min. Run detached, poll the log, don't kill early.
- Subagents `git add` ONLY their task's paths; never stage `CLAUDE.md`, `.axon/`,
  `docs/agents/`, `rpgm`, `*/bin/`. Commit `--no-gpg-sign`.

## Risk to watch

TEI `/rerank` request/response shape is assumed `{query, texts[]}` → `[{index, score}]`.
Task 4's Testcontainers integration test validates it against real TEI — if it differs,
fix `TeiRerankAdapter.RerankResult` + `TeiRerankAdapterTest` before the eval tasks.
