# Phase 2 — Handoff Note

> Snapshot of where the `feat/phase-2-eval` branch stands so the next session
> (yours or a fresh agent's) can resume without re-reading the whole chat.
> Living document — update before stopping work, delete when Phase 2 merges.
>
> **Status (2026-06-17):** the observability half, the hardening work (ArchUnit boundary test, `/v1/ingest` allowlist + ADR-013), and the LangChain4j retrieval path (ADR-014) are now **merged to `master`** (the `feat/phase-2-eval` branch is gone). The eval half (golden Q&A dataset, #8) remains the open Phase 2 item.

## TL;DR

- **Branch**: `feat/phase-2-eval`, 9 commits ahead of `master`
- **Build**: green, 22 unit tests passing
- **Observability half of Phase 2**: shipped end-to-end (audit log, Prometheus, Grafana, ADR-012, walkthrough)
- **Eval half of Phase 2**: blocked on the golden Q&A dataset (`#8`), which needs the D&D 5e PHB in hand
- **Hardening work in flight (uncommitted)**: ArchUnit hexagonal-boundary test, path allowlist + `@Profile("local")` on `/v1/ingest`, ADR-013 for that decision, two new unit tests. Not landed yet — see § Uncommitted below.

## Committed work on this branch

| Commit    | Subject                                                           | Task |
| --------- | ----------------------------------------------------------------- | ---- |
| `e5463aa` | fix(domain): add missing `LlmResult` record — master was broken    | —    |
| `05ee9bf` | refactor(prompts): drop dead `rag-user.st` + lang detection       | #4 #7 |
| `8e08867` | refactor(QW2): `RetrievalProperties` centralises topK / threshold | #5   |
| `1d85621` | feat(QW3): Bean Validation + RFC 9457 violation responses          | #6   |
| `500aba1` | feat(E2): prompt versioning header + structured per-query audit   | #9   |
| `3a4ee2a` | feat(O1): Micrometer meters (latency / tokens / cost / ingestion / embedding) | #15  |
| `8267cde` | feat(O2): Prometheus + Grafana via docker-compose                  | #16  |
| `a5506c1` | feat(O3): Grafana dashboard JSON, 8 panels                         | #17  |
| `501789b` | docs(observability): ADR-012 + walkthrough + README update        | D1–D3 |

## Uncommitted (local) — hardening work added in parallel

Files modified or new on the working tree that aren't in any commit yet:

| Path                                                                    | What it adds |
| ----------------------------------------------------------------------- | ------------ |
| `app/build.gradle`                                                       | ArchUnit `archunit-junit5:1.3.0` testImplementation |
| `app/src/main/java/.../config/IngestionProperties.java` (new)            | `rpg.ingestion.allowed-roots` @ConfigurationProperties |
| `app/src/main/java/.../adapter/inbound/rest/IngestionController.java`   | `@Profile("local")`, path normalised + allowlisted before ingestion |
| `app/src/main/resources/application-local.yml`                           | declares `rpg.ingestion.allowed-roots` |
| `app/src/test/java/.../architecture/HexagonalBoundaryTest.java` (new)   | enforces ports-and-adapters rules via ArchUnit |
| `app/src/test/java/.../unit/IngestionControllerTest.java` (new)         | covers the new allowlist behaviour |
| `app/src/test/java/.../unit/QueryUseCaseTest.java` (new)                 | covers QueryUseCase orchestration |
| `docs/adr/ADR-004-hexagonal-architecture.md`                             | references the new boundary test |
| `docs/adr/ADR-013-dev-only-ingest-endpoint.md` (new)                     | locks in the local-only-ingest + allowlist decision |

### When you resume — first 10 minutes

1. Verify the new tests are green: `./gradlew :app:test`
2. Run ArchUnit explicitly if the boundary rules surface anything:
   `./gradlew :app:test --tests 'com.rpgmaster.app.architecture.*'`
3. Read `ADR-013-dev-only-ingest-endpoint.md` once — confirm the security narrative reads right
4. Commit the hardening as one or two focused commits, suggested split:
   - `feat(security): @Profile("local") + path allowlist on /v1/ingest` (controller + IngestionProperties + application-local.yml + ADR-013 + IngestionControllerTest)
   - `test(arch): ArchUnit hexagonal-boundary enforcement` (build.gradle + HexagonalBoundaryTest + ADR-004 update + QueryUseCaseTest if it’s really about orchestration)
5. Push the branch, decide whether to open the PR or keep iterating until Phase 2 closes

## ADR numbering — collision resolved

`ADR-013` was originally reserved for "Eval harness design" in the task plan, but the user landed `ADR-013-dev-only-ingest-endpoint.md` first. The eval design ADR is now **ADR-014** — task `#14` has been renamed accordingly. Numbering on disk is the source of truth.

## Open tasks — what's still pending

Blocked by the dataset (`#8`):

| #   | Task                                  | Unblocks |
| --- | ------------------------------------- | -------- |
| #8  | E1 — Golden Q&A dataset (~30 EN+PT)   | #10      |
| #10 | E3 — `eval/` Gradle subproject         | #11 #12 |
| #11 | E4 — retrieval metrics (recall@k …)    | #13      |
| #12 | E5 — answer metrics (keyword v1)       | #13      |
| #13 | E6 — markdown baseline report          | #14 #18 |
| #14 | E7 — ADR-014 eval harness design       | #19      |
| #18 | W1 — eval badges + README              | #19      |
| #19 | W2 — LinkedIn article #2               | —        |

### #8 needs you, not an agent

The five decisions to make with the PHB open:

1. **Distribution by category** — how many of `spell` / `class-feature` / `combat-rule` / `condition` / `lore` ?
2. **EN/PT split** — 50/50 or weighted? PT translation source (fan translation? AI-translated?)
3. **Page ground-truth** — every question gets an `expected_pages` list; only you can confirm those
4. **Trap questions** — how many "Not found in the rulebook" style negatives to guard against hallucination?
5. **Phrasing tone** — natural-language (`"how much damage does fireball deal?"`) vs precise (`"What is the casting time, range, and damage of the Fireball spell?"`)

Recommended kickoff for the dataset:

1. We draft the JSON schema together (probably ~10 fields per entry)
2. You write 5 exemplary questions covering 5 different categories
3. I batch the remaining 25 in your style, you ratify
4. Schema gets committed first, dataset right after

## Decisions already locked in (don't relitigate)

- **Bedrock & Kafka deferred to Phase 5** — see README phase plan
- **NVIDIA NIM is the cloud-free LLM provider for Phase 3** — beats Groq because of model variety and embedding option
- **Eval uses keyword match v1, not LLM-as-judge** — cost, determinism, speed. LLM judge revisited in Phase 3
- **Observability contract is load-bearing** — ADR-012 binds metric names, audit log shape, prompt versioning. Renames need a follow-up ADR
- **`#1.0` is the active prompt version** — bump together with template body, never separately
- **Cost meter only registers when cost > 0** — keeps Prometheus clean during free local dev

## Validation checklist before merging Phase 2

- [x] Unit tests green (22)
- [ ] `./gradlew integrationTest` green (Testcontainers — last verified on master, not on this branch)
- [ ] `docker compose up` brings up all 5 services without errors
- [ ] `curl localhost:8082/actuator/prometheus | grep ^rpg_` returns the meters after a query
- [ ] Prometheus target `rpg-master-app` is `UP` at `localhost:9090/targets`
- [ ] Grafana dashboard "RPG Master AI / RAG Operations" loads with all 8 panels (no `No data` after running queries)
- [ ] Audit log line is parseable JSON (`tail -f` and `jq .` it during a query)
- [ ] Eval harness ran at least once and committed `eval/reports/<date>.md` — blocked on dataset

## Pointers

- Roadmap & phases: [README.md § Implementation Phases](../README.md#implementation-phases)
- Observability contract: [ADR-012](adr/ADR-012-observability-contract.md)
- Operator walkthrough: [observability.md](observability.md)
- Hexagonal boundary rules: [ADR-004](adr/ADR-004-hexagonal-architecture.md) + `HexagonalBoundaryTest`
- Dev-only ingest endpoint: [ADR-013](adr/ADR-013-dev-only-ingest-endpoint.md)
- Gap analysis (still mostly relevant): [gap-analysis.md](gap-analysis.md)
