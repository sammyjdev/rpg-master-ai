# Eval Baseline (Slice 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a golden Q&A dataset and a baseline eval that measures retrieval quality (deterministic `recall@k`/`MRR` swept over k) plus an answer-quality guardrail (GNOMON), so a later reranking change is a measured experiment.

**Architecture:** A pure unit-tested metric core (`recall@k`, `MRR`) with zero infra dependency; a `./gradlew eval` harness that loads the golden set, embeds each question with the real `EmbeddingPort` (bge-m3/Ollama), calls `VectorStorePort.search` directly against the ingested Qdrant corpus, sweeps `k ∈ {3,5,8,10}`, and writes a Markdown report; and GNOMON wired externally against the existing OpenAI-compatible endpoint for `faithfulness`/`context_precision`.

**Tech Stack:** Java 21, Gradle multi-module, Spring Boot, JUnit 5 + AssertJ, Jackson (JSON), existing `VectorStorePort`/`EmbeddingPort` (Qdrant + Ollama bge-m3), GNOMON (external Python, OpenAI-compat target).

## Global Constraints

- Build tool: **Gradle** (`./gradlew`), multi-module; app module is `:app`. No Maven.
- Hexagonal architecture (ADR-004): new code consumes ports; do not bypass them. `HexagonalBoundaryTest` (ArchUnit) enforces this — new packages must not violate it.
- No new production dependency unless a task explicitly adds it. Jackson is already on the classpath via Spring Boot.
- No Lombok (ADR-011). Use records and plain Java.
- Golden set location: `app/src/test/resources/golden-qa.json`. Reports: `eval/reports/`.
- Slice 1 does **not** modify the retrieval pipeline (no reranking, no BM25, no chunking/threshold change).
- `k` is swept `{3, 5, 8, 10}`; never hardcode a single retrieval depth as "the answer".
- Metric-math unit test must be CI-safe (no Docker/Ollama). The `eval` harness is dev-run (needs live stack) and is not a CI gate in this slice.

---

### Task 1: Golden Q&A dataset schema + loader (with validation)

**Files:**
- Create: `app/src/test/resources/golden-qa.json`
- Create: `app/src/test/java/com/rpgmaster/app/eval/GoldenCase.java`
- Create: `app/src/test/java/com/rpgmaster/app/eval/GoldenSet.java`
- Test: `app/src/test/java/com/rpgmaster/app/eval/GoldenSetTest.java`

**Interfaces:**
- Produces:
  - `record RelevantPage(String rulebookId, int pageNumber)`
  - `record GoldenCase(String id, String question, String expectedAnswer, List<RelevantPage> relevantPages)`
  - `final class GoldenSet` with `static List<GoldenCase> load(java.io.InputStream json)` — parses JSON, validates each case (non-blank `id`/`question`/`expectedAnswer`, non-empty `relevantPages`, each page `pageNumber >= 1`, non-blank `rulebookId`), throws `IllegalArgumentException` with the offending `id` on any violation.

- [ ] **Step 1: Write `golden-qa.json` with 3 seed cases** (the full ~20-30 set is authored later; 3 is enough to build against)

```json
[
  {
    "id": "gq-001",
    "question": "What is an Ankheg's armor class?",
    "expectedAnswer": "AC 14 (natural armor), 11 while prone.",
    "relevantPages": [{ "rulebookId": "dnd-5e-mm", "pageNumber": 21 }]
  },
  {
    "id": "gq-002",
    "question": "How much damage does a Fireball spell deal?",
    "expectedAnswer": "8d6 fire damage on a failed Dexterity save, half on success.",
    "relevantPages": [{ "rulebookId": "dnd-5e-phb", "pageNumber": 241 }]
  },
  {
    "id": "gq-003",
    "question": "Quanto de dano causa uma bola de fogo?",
    "expectedAnswer": "8d6 de dano de fogo; metade com sucesso no teste de Destreza.",
    "relevantPages": [{ "rulebookId": "dnd-5e-phb", "pageNumber": 241 }]
  }
]
```

- [ ] **Step 2: Write the failing test**

```java
package com.rpgmaster.app.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class GoldenSetTest {

    @Test
    void loadsAndValidatesTheBundledGoldenSet() {
        var in = getClass().getResourceAsStream("/golden-qa.json");
        List<GoldenCase> cases = GoldenSet.load(in);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(3);
        assertThat(cases.get(0).id()).isEqualTo("gq-001");
        assertThat(cases.get(0).relevantPages())
                .containsExactly(new RelevantPage("dnd-5e-mm", 21));
    }

    @Test
    void rejectsCaseWithNoRelevantPages() {
        var bad = """
            [{"id":"x","question":"q","expectedAnswer":"a","relevantPages":[]}]
            """;
        var in = new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> GoldenSet.load(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GoldenSetTest'`
Expected: FAIL — `GoldenSet`/`GoldenCase`/`RelevantPage` do not exist (compilation error).

- [ ] **Step 4: Implement the records and loader**

`RelevantPage.java`:
```java
package com.rpgmaster.app.eval;

public record RelevantPage(String rulebookId, int pageNumber) {}
```

`GoldenCase.java`:
```java
package com.rpgmaster.app.eval;

import java.util.List;

public record GoldenCase(
        String id,
        String question,
        String expectedAnswer,
        List<RelevantPage> relevantPages) {}
```

`GoldenSet.java`:
```java
package com.rpgmaster.app.eval;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class GoldenSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenSet() {}

    public static List<GoldenCase> load(InputStream json) {
        List<GoldenCase> cases;
        try {
            cases = MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, GoldenCase.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("golden-qa.json is not valid JSON", e);
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("golden-qa.json is empty");
        }
        for (GoldenCase c : cases) {
            validate(c);
        }
        return cases;
    }

    private static void validate(GoldenCase c) {
        String id = c.id();
        if (isBlank(id)) {
            throw new IllegalArgumentException("golden case has blank id");
        }
        if (isBlank(c.question()) || isBlank(c.expectedAnswer())) {
            throw new IllegalArgumentException("golden case '" + id + "' has blank question or expectedAnswer");
        }
        if (c.relevantPages() == null || c.relevantPages().isEmpty()) {
            throw new IllegalArgumentException("golden case '" + id + "' has no relevantPages");
        }
        for (RelevantPage p : c.relevantPages()) {
            if (isBlank(p.rulebookId()) || p.pageNumber() < 1) {
                throw new IllegalArgumentException("golden case '" + id + "' has an invalid relevantPage");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GoldenSetTest'`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/test/resources/golden-qa.json \
        app/src/test/java/com/rpgmaster/app/eval/RelevantPage.java \
        app/src/test/java/com/rpgmaster/app/eval/GoldenCase.java \
        app/src/test/java/com/rpgmaster/app/eval/GoldenSet.java \
        app/src/test/java/com/rpgmaster/app/eval/GoldenSetTest.java
git commit -S -m "test: golden Q&A dataset schema + validating loader"
```

---

### Task 2: Retrieval metric core — recall@k and MRR

**Files:**
- Create: `app/src/test/java/com/rpgmaster/app/eval/RetrievalMetrics.java`
- Test: `app/src/test/java/com/rpgmaster/app/eval/RetrievalMetricsTest.java`

**Interfaces:**
- Consumes: `RelevantPage` (Task 1).
- Produces:
  - `record RetrievedPage(String rulebookId, int pageNumber)` — a page identity for scoring (derived from `SourceChunk`).
  - `RetrievalMetrics.recallAtK(List<RetrievedPage> retrieved, List<RelevantPage> relevant, int k)` → `double` in `[0,1]`: fraction of `relevant` pages present within the first `k` `retrieved` (deduped by identity, order preserved). Returns `0.0` if `relevant` is empty is impossible (loader forbids it); guard anyway by returning `0.0`.
  - `RetrievalMetrics.reciprocalRank(List<RetrievedPage> retrieved, List<RelevantPage> relevant)` → `double`: `1/rank` of the first retrieved page that is relevant (1-indexed), `0.0` if none.
  - Page identity match is `rulebookId` + `pageNumber` equality.

- [ ] **Step 1: Write the failing test**

```java
package com.rpgmaster.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    private static final String RB = "dnd-5e-phb";

    private RetrievedPage r(int page) { return new RetrievedPage(RB, page); }
    private RelevantPage rel(int page) { return new RelevantPage(RB, page); }

    @Test
    void recallIsOneWhenRelevantPageIsWithinK() {
        var retrieved = List.of(r(10), r(241), r(5));   // relevant at position 2
        var relevant = List.of(rel(241));

        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 3)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 1)).isEqualTo(0.0);
    }

    @Test
    void recallIsFractionOfRelevantPagesFound() {
        var retrieved = List.of(r(241), r(99));
        var relevant = List.of(rel(241), rel(300));      // only one of two present

        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 8)).isEqualTo(0.5);
    }

    @Test
    void reciprocalRankUsesFirstRelevantPosition() {
        var retrieved = List.of(r(10), r(20), r(241));   // first relevant at rank 3
        var relevant = List.of(rel(241));

        assertThat(RetrievalMetrics.reciprocalRank(retrieved, relevant))
                .isEqualTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void reciprocalRankIsZeroWhenNoRelevantRetrieved() {
        assertThat(RetrievalMetrics.reciprocalRank(List.of(r(1), r(2)), List.of(rel(999))))
                .isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.RetrievalMetricsTest'`
Expected: FAIL — `RetrievalMetrics`/`RetrievedPage` do not exist.

- [ ] **Step 3: Implement the metric core**

`RetrievalMetrics.java` (define `RetrievedPage` as a nested record to keep the pair together):
```java
package com.rpgmaster.app.eval;

import java.util.List;

public final class RetrievalMetrics {

    public record RetrievedPage(String rulebookId, int pageNumber) {}

    private RetrievalMetrics() {}

    public static double recallAtK(List<RetrievedPage> retrieved,
                                   List<RelevantPage> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        List<RetrievedPage> topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        long found = relevant.stream()
                .filter(rel -> topK.stream().anyMatch(got -> matches(got, rel)))
                .count();
        return (double) found / relevant.size();
    }

    public static double reciprocalRank(List<RetrievedPage> retrieved,
                                        List<RelevantPage> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            RetrievedPage got = retrieved.get(i);
            if (relevant.stream().anyMatch(rel -> matches(got, rel))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static boolean matches(RetrievedPage got, RelevantPage rel) {
        return got.pageNumber() == rel.pageNumber()
                && got.rulebookId().equals(rel.rulebookId());
    }
}
```

Update the test import: `RetrievedPage` is `RetrievalMetrics.RetrievedPage`. Change the helper in the test to `new RetrievalMetrics.RetrievedPage(RB, page)` and the field/type references accordingly, OR add `import static ...` — simplest: replace `RetrievedPage` with `RetrievalMetrics.RetrievedPage` in the test's `r(...)` helper return type and constructor.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.RetrievalMetricsTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/rpgmaster/app/eval/RetrievalMetrics.java \
        app/src/test/java/com/rpgmaster/app/eval/RetrievalMetricsTest.java
git commit -S -m "test: recall@k and MRR metric core"
```

---

### Task 3: Eval harness — sweep k against live retrieval, write report

**Files:**
- Create: `app/src/integrationTest/java/com/rpgmaster/app/eval/RetrievalBaselineEval.java`
- Create: `app/build.gradle` — register a `eval` task (modify the tasks section)
- Create: `eval/reports/.gitkeep`

**Interfaces:**
- Consumes: `GoldenSet.load` (Task 1), `RetrievalMetrics.recallAtK`/`reciprocalRank` + `RetrievalMetrics.RetrievedPage` (Task 2), `EmbeddingPort.embed(String)` → `List<Float>`, `VectorStorePort.search(String rulebookId, List<Float> queryVector, int topK, float threshold)` → `List<SourceChunk>` where `SourceChunk` has `pageNumber()` and `rulebookId()`.
- Produces: a Markdown report at `eval/reports/retrieval-<epochMillis>.md` with a row per k in `{3,5,8,10}` giving mean `recall@k` and mean `MRR` over the golden set, and a recommended k line.

**Why integrationTest source set:** it already has Testcontainers + Spring context wiring and is excluded from the CI `test` task. The eval is corpus-dependent (needs the ingested Qdrant), so it belongs with integration code, not the CI-gated unit tests.

- [ ] **Step 1: Write the harness as a `@SpringBootTest` driven entry**

The simplest runnable harness that reuses Spring wiring is a `@SpringBootTest` that is invoked explicitly (not part of the default suite). Use `@Autowired` ports, iterate the golden set, sweep k with a single search at `max(k)` then truncate (one embedding + one search per question, sliced per k — cheaper than re-searching).

```java
package com.rpgmaster.app.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

/**
 * Baseline retrieval eval. NOT a CI test — run explicitly against a live stack
 * (docker-compose Qdrant + Ollama, corpus already ingested):
 *   ./gradlew :app:eval
 * Writes eval/reports/retrieval-<millis>.md.
 */
@SpringBootTest
@ActiveProfiles("eval")
class RetrievalBaselineEval {

    private static final int[] K_SWEEP = {3, 5, 8, 10};
    private static final float THRESHOLD = 0.3f;

    @Autowired EmbeddingPort embeddingPort;
    @Autowired VectorStorePort vectorStorePort;

    @Test
    void recordBaseline() throws IOException {
        var cases = GoldenSet.load(getClass().getResourceAsStream("/golden-qa.json"));
        int maxK = 10;

        // per-k accumulators
        double[] recallSum = new double[K_SWEEP.length];
        double[] rrSum = new double[K_SWEEP.length];

        for (GoldenCase c : cases) {
            var vector = embeddingPort.embed(c.question());
            // search once at maxK per rulebook of the case's first relevant page
            String rulebook = c.relevantPages().get(0).rulebookId();
            List<SourceChunk> hits = vectorStorePort.search(rulebook, vector, maxK, THRESHOLD);

            List<RetrievalMetrics.RetrievedPage> retrieved = new ArrayList<>();
            for (SourceChunk h : hits) {
                retrieved.add(new RetrievalMetrics.RetrievedPage(h.rulebookId(), h.pageNumber()));
            }

            for (int i = 0; i < K_SWEEP.length; i++) {
                recallSum[i] += RetrievalMetrics.recallAtK(retrieved, c.relevantPages(), K_SWEEP[i]);
                rrSum[i] += RetrievalMetrics.reciprocalRank(
                        retrieved.subList(0, Math.min(K_SWEEP[i], retrieved.size())),
                        c.relevantPages());
            }
        }

        int n = cases.size();
        StringBuilder md = new StringBuilder();
        md.append("# Retrieval baseline\n\n");
        md.append("Cases: ").append(n).append(", threshold: ").append(THRESHOLD).append("\n\n");
        md.append("| k | mean recall@k | mean MRR |\n|---|---|---|\n");
        for (int i = 0; i < K_SWEEP.length; i++) {
            md.append("| ").append(K_SWEEP[i]).append(" | ")
              .append(String.format("%.3f", recallSum[i] / n)).append(" | ")
              .append(String.format("%.3f", rrSum[i] / n)).append(" |\n");
        }

        Path dir = Path.of("..", "eval", "reports");
        Files.createDirectories(dir);
        Path out = dir.resolve("retrieval-" + System.currentTimeMillis() + ".md");
        Files.writeString(out, md.toString());
        System.out.println("Wrote " + out.toAbsolutePath());
        System.out.println(md);
    }
}
```

- [ ] **Step 2: Register the `eval` Gradle task**

In `app/build.gradle`, after the `integrationTest` task registration, add:
```groovy
tasks.register('eval', Test) {
    description = 'Records the retrieval baseline against a live stack (Qdrant + Ollama, corpus ingested). Not part of CI.'
    group = 'verification'
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnitPlatform()
    jvmArgs '-XX:+EnableDynamicAgentLoading'
    filter { includeTestsMatching 'com.rpgmaster.app.eval.RetrievalBaselineEval' }
    outputs.upToDateWhen { false }   // always re-run; it records fresh numbers
}
```

- [ ] **Step 3: Create the reports directory placeholder**

```bash
mkdir -p eval/reports && touch eval/reports/.gitkeep
```

- [ ] **Step 4: Run the harness against the live stack**

Preconditions: `docker compose up -d qdrant ollama`, corpus ingested (see README ingest step), Ollama has bge-m3 pulled.
Run: `./gradlew :app:eval`
Expected: task passes; console prints the table and the report path; a file appears at `eval/reports/retrieval-<millis>.md`.

If the stack/corpus is absent the Spring context or search will fail — that is expected outside a provisioned dev machine; this task is not run in CI.

- [ ] **Step 5: Commit the harness + task (not the generated report yet)**

```bash
git add app/src/integrationTest/java/com/rpgmaster/app/eval/RetrievalBaselineEval.java \
        app/build.gradle eval/reports/.gitkeep
git commit -S -m "test: retrieval baseline eval harness with k sweep (gradlew eval)"
```

---

### Task 4: Record the baseline numbers

**Files:**
- Create: `docs/eval-baseline.md`

**Interfaces:**
- Consumes: the report produced by `./gradlew :app:eval` (Task 3).

- [ ] **Step 1: Run the eval and copy the resulting table into a durable doc**

Run: `./gradlew :app:eval` (against the live stack).
Then write `docs/eval-baseline.md`:
```markdown
# Eval baseline (Slice 1)

Recorded from `./gradlew :app:eval` on <DATE>. Corpus: <rulebooks + counts>.

## Retrieval (deterministic)

| k | mean recall@k | mean MRR |
|---|---|---|
| 3 | <fill from report> | <fill> |
| 5 | <fill> | <fill> |
| 8 | <fill> | <fill> |
| 10 | <fill> | <fill> |

Recommended k for this corpus: <the k where recall/MRR plateaus>. The current
production default is 8 (unvalidated tiebreak) — this is the first measured basis
to keep or change it.

## Answer quality (GNOMON) — see Task 5

| metric | mean | 95% CI |
|---|---|---|
| faithfulness | <fill> | <fill> |
| context_precision | <fill> | <fill> |
```

The `<fill>` values come directly from the generated report and the GNOMON run (Task 5); replace them with the actual numbers. This doc is the target Slice 2 must beat.

- [ ] **Step 2: Commit**

```bash
git add docs/eval-baseline.md
git commit -S -m "docs: record retrieval baseline numbers (Slice 1)"
```

---

### Task 5: Wire GNOMON as the answer-quality guardrail

**Files:**
- Create: `eval/gnomon/config.toml`
- Create: `eval/gnomon/dataset.jsonl` (generated from the golden set)
- Create: `eval/gnomon/README.md` (runbook)
- Create: `app/src/integrationTest/java/com/rpgmaster/app/eval/GnomonDatasetExport.java`

**Interfaces:**
- Consumes: `GoldenSet.load` (Task 1); the running app's `/v1/chat/completions` (`OpenAiCompatibleController`, model `all-rulebooks`); GNOMON at `~/dev/gnomon-eval` (OpenAI-compat `[target]`).
- Produces: a GNOMON dataset + config that, run per its README, reports `faithfulness` + `context_precision` with 95% CIs against the live RPG_MASTER instance.

**Note on GNOMON dataset format:** GNOMON reads a versioned dataset of eval cases and scores responses from the target via an LLM judge. The exact field names live in GNOMON's `config/example.toml` + its dataset schema (`~/dev/gnomon-eval`, see `src/gnomon/domain/models.py::EvalCase`). Read that file first and mirror its field names exactly; the export below assumes `{question, reference_answer}` per line and MUST be adjusted to match `EvalCase`.

- [ ] **Step 1: Read GNOMON's EvalCase schema and example config**

Run:
```bash
sed -n '1,60p' ~/dev/gnomon-eval/src/gnomon/domain/models.py
cat ~/dev/gnomon-eval/config/example.toml
```
Note the exact dataset field names and the `[target]` block shape. Use them verbatim in Steps 2-3.

- [ ] **Step 2: Write a small exporter that turns the golden set into GNOMON's dataset**

```java
package com.rpgmaster.app.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exports golden-qa.json into GNOMON's dataset format at eval/gnomon/dataset.jsonl.
 * Field names below MUST match GNOMON's EvalCase (see Step 1). Run:
 *   ./gradlew :app:test --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'
 */
class GnomonDatasetExport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void export() throws IOException {
        var cases = GoldenSet.load(getClass().getResourceAsStream("/golden-qa.json"));
        var sb = new StringBuilder();
        for (GoldenCase c : cases) {
            // ADJUST field names to GNOMON's EvalCase (Step 1).
            var node = MAPPER.createObjectNode();
            node.put("question", c.question());
            node.put("reference_answer", c.expectedAnswer());
            sb.append(MAPPER.writeValueAsString(node)).append("\n");
        }
        Path out = Path.of("..", "eval", "gnomon", "dataset.jsonl");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString());
        System.out.println("Wrote " + out.toAbsolutePath());
    }
}
```

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'`
Expected: PASS; `eval/gnomon/dataset.jsonl` written with one line per golden case.

- [ ] **Step 3: Write GNOMON `config.toml` pointing at the live app**

Mirror `~/dev/gnomon-eval/config/example.toml`, changing only the `[target]` block to the OpenAI-compat endpoint. Fill exact keys from Step 1; the shape is:
```toml
[target]
type = "openai_compat"
base_url = "http://localhost:8080/v1"
model = "all-rulebooks"

[dataset]
path = "dataset.jsonl"

[gate]
faithfulness_min = 0.0        # record-only on first run; set a floor after baseline
context_precision_min = 0.0
```
(Port 8080 is the Spring Boot default; adjust if `application.yml` overrides `server.port`.)

- [ ] **Step 4: Write the runbook**

`eval/gnomon/README.md`:
```markdown
# GNOMON answer-quality guardrail

Measures faithfulness + context_precision (each with 95% CI) of RPG_MASTER's
answers, via GNOMON's LLM judge against the live OpenAI-compatible endpoint.

## Run
1. Start RPG_MASTER (corpus ingested): `./gradlew :app:bootRun` (or the jar).
2. Regenerate the dataset if the golden set changed:
   `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'`
3. From ~/dev/gnomon-eval, run the harness against this config
   (see gnomon-eval README for the exact command; it points [target] at
   http://localhost:8080/v1).
4. Copy the reported faithfulness / context_precision + CIs into
   docs/eval-baseline.md (Task 4).
```

- [ ] **Step 5: Commit**

```bash
git add eval/gnomon/config.toml eval/gnomon/README.md \
        app/src/integrationTest/java/com/rpgmaster/app/eval/GnomonDatasetExport.java
# dataset.jsonl is generated; commit it too so the versioned dataset is reproducible
git add eval/gnomon/dataset.jsonl
git commit -S -m "test: wire GNOMON answer-quality guardrail (dataset export + config + runbook)"
```

---

### Task 6: Expand the golden set to ~20-30 cases

**Files:**
- Modify: `app/src/test/resources/golden-qa.json`

**Interfaces:**
- Consumes: nothing new. `GoldenSetTest` (Task 1) already validates the file on every `./gradlew :app:test`.

- [ ] **Step 1: Author ~20-30 cases covering the target failure modes**

Add cases (keeping the Task 1 schema) across:
- exact-match rule/monster lookups (`Ankheg` AC, specific spell save DCs, condition definitions),
- spell blocks that span page/chunk boundaries,
- PT/EN pairs for the same fact (multilingual retrieval),
- a few "not in the rulebook" style questions where `relevantPages` points to the page that *should* answer it.

Each case MUST have a real, verifiable `relevantPages` (open the PDF, confirm the page). A wrong page label silently corrupts recall.

- [ ] **Step 2: Verify the loader still accepts the expanded set**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GoldenSetTest'`
Expected: PASS; `hasSizeGreaterThanOrEqualTo(3)` still holds (now ~20-30).

- [ ] **Step 3: Re-run the baseline with the full set and refresh the recorded numbers**

Run: `./gradlew :app:eval` then update `docs/eval-baseline.md` (Task 4) with the fuller-N numbers.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/resources/golden-qa.json docs/eval-baseline.md
git commit -S -m "test: expand golden Q&A set to full coverage + refresh baseline"
```

---

## Self-Review

**Spec coverage:**
- Golden set (schema, location, size) → Task 1 + Task 6. ✓
- Deterministic retrieval metric, k swept `{3,5,8,10}` → Task 2 (math) + Task 3 (harness). ✓
- GNOMON guardrail via existing `/v1` endpoint → Task 5. ✓
- Recorded baseline for Slice 2 to beat → Task 4 (+ refresh in Task 6). ✓
- No pipeline changes; ports not bypassed → all tasks consume `EmbeddingPort`/`VectorStorePort`; no production code touched except the `eval` Gradle task. ✓
- Metric-math unit test is CI-safe; harness is dev-run → Task 2 in `src/test`, Task 3 in `src/integrationTest` + dedicated `eval` task. ✓
- Loader validation of golden set → Task 1 (rejects empty relevantPages, blank fields, page < 1). ✓

**Placeholder scan:** The only intentional `<fill>` values are in `docs/eval-baseline.md` (Task 4) — these are runtime measurements that cannot be known until the harness runs, and the task explains exactly where each comes from. GNOMON field names in Task 5 are explicitly gated behind Step 1 (read the real schema) rather than guessed. No other TODOs.

**Type consistency:** `RelevantPage(rulebookId, pageNumber)` used identically in Tasks 1/2/3. `RetrievalMetrics.RetrievedPage` (nested record) used consistently in Tasks 2/3. `GoldenCase` fields (`id`, `question`, `expectedAnswer`, `relevantPages`) match across Tasks 1/3/5. `VectorStorePort.search(rulebookId, vector, topK, threshold)` and `SourceChunk.pageNumber()/rulebookId()` match the real interfaces verified in the repo.
