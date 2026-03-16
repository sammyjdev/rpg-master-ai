# Implementation Journal: Problems, Resolutions, And Trade-Offs

This journal records the main issues encountered during Step 1 and the trade-offs accepted to keep delivery moving. Each trade-off is linked to the ADR that justifies the decision where applicable.

## Problem 1: English-first embedding model failed on multilingual retrieval

**Symptom**

English questions against Portuguese-translated PDFs returned zero or weak matches.

**Root Cause**

The original embedding setup was not strong enough for cross-lingual retrieval.

**Attempted Solutions**

1. Lower retrieval threshold.
2. Increase top-K.
3. Replace the embedding model.

**Final Resolution**

Moved to `bge-m3` embeddings and re-ingested the corpus.

**Trade-Off**

Higher dimensional vectors and more resource usage were accepted to improve multilingual recall.

**Related ADR**

- [ADR-008](adr/ADR-008-bge-m3-embeddings.md)

## Problem 2: Small chat model retrieved correct chunks but answered weakly

**Symptom**

Retrieved context was correct, but the answer synthesis was incomplete or low quality.

**Root Cause**

The local chat model was too small for consistent rule synthesis.

**Attempted Solutions**

1. Prompt tightening.
2. Page citation reinforcement.
3. Model upgrade.

**Final Resolution**

Moved from smaller local models to `qwen2.5:7b` for Step 1 quality validation.

**Trade-Off**

Higher memory and compute cost were accepted to make demos and benchmarks credible.

**Related ADR**

- [ADR-002](adr/ADR-002-ollama-for-local-models.md)

## Problem 3: Similarity threshold blocked relevant multilingual chunks

**Symptom**

Queries such as Fireball lookups could miss obviously relevant context.

**Root Cause**

The threshold was too strict for noisy OCR text and cross-lingual retrieval.

**Attempted Solutions**

1. Keep strict thresholds and rely on prompt changes.
2. Increase top-K only.
3. Lower the threshold and validate empirically.

**Final Resolution**

Set the default threshold to `0.3` and validated with a five-question benchmark.

**Trade-Off**

Recall was prioritized over precision, accepting that some weaker chunks may enter the top-K set.

**Related ADR**

- [ADR-009](adr/ADR-009-similarity-threshold-0.3.md)

## Problem 4: Answers lacked source citations

**Symptom**

The model answered correctly but did not reliably mention the source page.

**Root Cause**

Page metadata was not made explicit enough in the context presented to the model.

**Final Resolution**

Each chunk is now prefixed with `[Source: rulebook_id | Page: N]`, and the system prompt explicitly requires page citations.

**Trade-Off**

Prompt and context verbosity increased slightly to get more reviewable answers.

**Related ADR**

- [ADR-007](adr/ADR-007-chunking-strategy-400-80.md)

## Problem 5: Output language sometimes did not match the question language

**Symptom**

English questions sometimes received Portuguese answers and vice versa.

**Root Cause**

The model followed context language too strongly when the corpus language differed from the user question.

**Final Resolution**

Language matching is enforced in two places: the system prompt and an explicit `IMPORTANT: Answer in {language}.` suffix in the user prompt.

**Trade-Off**

The prompt now contains some intentional redundancy to improve compliance on small local models.

## Problem 6: First language detector produced false positives

**Symptom**

Short English questions were classified as Portuguese due to ambiguous words.

**Root Cause**

The original heuristic relied on words that occur in both languages.

**Final Resolution**

Replaced it with accented-character detection plus a list of unambiguous Portuguese words.

**Trade-Off**

The solution remains heuristic instead of model-based, because it is cheap, deterministic, and fast enough for Step 1.

## Problem 7: Language detector still missed plain Portuguese questions without accents

**Symptom**

Questions like `Como funciona o sistema de descanso...` were not detected as Portuguese.

**Root Cause**

The input had no accented characters and did not match the earlier stopword list.

**Final Resolution**

Expanded the Portuguese word list with RPG-domain vocabulary such as `funciona`, `descanso`, `personagem`, `magia`, and `combate`.

**Trade-Off**

The heuristic became more domain-specific in exchange for better Step 1 behavior.

## Problem 8: 200-token chunks fragmented rules and stat blocks

**Symptom**

Important rule segments were split across too many chunks, weakening retrieval context.

**Root Cause**

The chunk size was optimized for a smaller model context window rather than document structure.

**Final Resolution**

Moved to `400` max tokens with `80` token overlap and re-ingested all rulebooks.

**Trade-Off**

Fewer, larger chunks reduce fragmentation but can include more surrounding text than strictly necessary.

**Related ADR**

- [ADR-007](adr/ADR-007-chunking-strategy-400-80.md)

## Problem 9: Spring Shell was fragile for scripted ingestion

**Symptom**

Non-interactive ingestion through `bootRun --args` was unreliable for repeatable automation.

**Root Cause**

Spring Shell is a good interactive interface but a poor fit for scripted ingestion orchestration.

**Final Resolution**

Added a REST ingestion endpoint for local automation while keeping the CLI.

**Trade-Off**

The new endpoint accepts filesystem paths, which is a development shortcut and not a production-safe interface.

**Related ADR**

- [ADR-005](adr/ADR-005-monolith-first-step1.md)

## Problem 10: Git Bash paths were incompatible with Java Path handling on Windows

**Symptom**

`/c/Users/...` paths from Git Bash failed in the Java ingestion endpoint.

**Root Cause**

The shell and the JVM use different Windows path conventions.

**Final Resolution**

The `rpgm` script converts `/c/...` paths to `C:/...` before calling the API.

**Trade-Off**

Platform-specific normalization was added to keep the developer workflow simple on Windows.

## Problem 11: Qdrant Java client API was not obvious for HNSW tuning

**Symptom**

The initial implementation used the wrong API type names for HNSW configuration.

**Root Cause**

The client library separates collection-time and query-time configuration across different namespaces.

**Final Resolution**

Used `Collections.HnswConfigDiff` for index-time settings and `Points.SearchParams.setHnswEf(...)` for query-time tuning.

**Trade-Off**

The code now includes explicit HNSW settings, increasing configuration surface area for the benefit of predictable recall.

**Related ADR**

- [ADR-001](adr/ADR-001-qdrant-as-vector-store.md)

## Problem 12: Ollama CLI was not always available in the shell environment

**Symptom**

`ollama pull ...` was not available in PATH.

**Root Cause**

Environment setup varied across shells on Windows.

**Final Resolution**

Used the Ollama HTTP API to pull models instead of depending on the CLI.

**Trade-Off**

The workflow became more API-centric and less dependent on local shell configuration.

## Problem 13: Fan translation boilerplate polluted chunk quality

**Symptom**

Repeated disclaimer text and watermark-like noise were being embedded as if they were rules content.

**Root Cause**

The source PDFs contain recurring non-rulebook text and OCR artifacts.

**Final Resolution**

Added a boilerplate stripping heuristic in `PdfBoxChunkingAdapter` before chunk creation.

**Trade-Off**

Aggressive stripping can remove some legitimate text in edge cases, but it dramatically improves corpus quality for the current dataset.

## Problem 14: REST ingestion endpoint uses raw filesystem paths

**Symptom**

The new ingestion API is convenient locally but would be unsafe as a public interface.

**Root Cause**

The endpoint was designed to unblock Step 1 re-ingestion quickly.

**Final Resolution**

Keep the endpoint local-only and document multipart upload or object-store keys as the next-phase replacement.

**Trade-Off**

Developer speed was prioritized over production security in a bounded, documented manner.

**Related ADR**

- [ADR-005](adr/ADR-005-monolith-first-step1.md)

## Problem 15: Documentation drift appeared between target architecture and implemented architecture

**Symptom**

The README described future services and directories that were not present in the repository.

**Root Cause**

The project vision and roadmap advanced faster than the implementation.

**Final Resolution**

The architecture documents added in this step separate current Step 1 reality from future phases.

## Problem 16: Portuguese query without accented characters was answered in English

**Symptom**

A query like `"O que e Bola de Fogo?"` (without accent on `é`) returned an English answer despite
being a Portuguese question. The same query with the accent (`"O que é Bola de Fogo?"`) also returned
English before the fix, because `é` (`\u00e9`) was absent from the Unicode fast-path check in
`isLikelyPortuguese()`.

**Root Cause**

Two compounding defects in `SpringAiLlmAdapter.isLikelyPortuguese()`:

1. `é` (`\u00e9`) was missing from the Unicode fast-path character check. The fast path checked `ã õ ç à â ê` but not `é`, `í`, `ú`, or `ó` — the most common accented vowels in Brazilian Portuguese.
2. The `PT_STOPWORDS` set was too small (12 words). High-frequency Portuguese words like `que`, `como`, `uma`, `pode`, `foi`, `fogo` were absent, so plain-text PT queries that happened to avoid all accents fell through both detection layers and were treated as English.

**Investigation**

The defect was discovered while running live benchmarks against the running system. The query
`"O que é Bola de Fogo?"` sent via the OpenAI-compatible REST endpoint returned an English response.
Inspection of the `isLikelyPortuguese()` method confirmed the missing `\u00e9` character check.

**Final Resolution**

1. Added `\u00e9` (é) to the Unicode fast-path loop, with all 10 PT-specific characters now
   covered across three groups (`ã õ ç` / `à â ê` / `é í ú ó`).
2. Expanded `PT_STOPWORDS` from 12 words to 35+, categorized into: question words, common verbs,
   pronouns/prepositions, adverbs, and RPG domain vocabulary (`fogo`, `dano`, `ataque`, etc.).

**Verification**

Three test cases confirmed via the live API:
- `"O que é Bola de Fogo?"` — now returns Portuguese ✅
- `"O que e Bola de Fogo?"` (no accent) — now returns Portuguese ✅  
- `"What is Fireball?"` — still returns English (no regression) ✅

**Trade-Off**

The solution remains heuristic. A proper language detection library (e.g., Apache Tika's language
detector or `lingua`) is the correct long-term solution, but the heuristic is now demonstrably
correct for the EN + PT use case and avoids an additional runtime dependency.

**Related gap**

[gap-analysis.md A.2 Gap #4](gap-analysis.md) — language detection fragility.

**Trade-Off**

The documentation is now more explicit about what is implemented versus what is planned, reducing ambiguity for reviewers.
