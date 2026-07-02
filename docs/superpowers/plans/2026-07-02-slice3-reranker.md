# Cross-encoder reranking (Slice 3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a cross-encoder reranking step (bge-reranker-v2-m3 via TEI) between Qdrant retrieval and the LLM, behind a config toggle, and measure it against the Slice-2 baseline.

**Architecture:** A new `RerankPort` + `TeiRerankAdapter` (HTTP to a local TEI container). A shared `RetrievalService` does `search(topN) → optional rerank → topK` and is used by both the query path (`QueryUseCase`) and the eval harness, so eval measures exactly what production does. Reranking is off by default and enabled by config for the A/B eval.

**Tech Stack:** Java 21, Spring Boot 3.3 (RestClient, ConfigurationProperties), TEI (`text-embeddings-inference` CPU image) serving bge-reranker-v2-m3, Testcontainers (GenericContainer) + Spring `MockRestServiceServer` for tests, Qdrant + Ollama (existing).

## Global Constraints

- Java 21 only; hexagonal — business logic talks to ports, adapters do I/O. No Lombok (use records). Constructor injection only, fields `final`.
- Config prefix is **`rpg`** (matches existing `rpg.retrieval`): rerank props live under `rpg.rerank`. `@ConfigurationProperties` records in `com.rpgmaster.app.config` are auto-registered by the existing `@ConfigurationPropertiesScan`.
- Reranker model: **`BAAI/bge-reranker-v2-m3`**. TEI CPU image on the Mac (colima), published on host **:8090** (TEI listens on container port **80**).
- Pipeline: `search(topN=30) → rerank → topK`. Default `rpg.rerank.enabled=false`; `top-n=30`, `top-k=8` (existing).
- Eval sweeps k∈{3,5,8,10} → it requests the reranked list at `topK = maxK (=10)` and truncates per k. Only the query path uses topK=8.
- Reranker failure is **fail-loud** — never silently fall back to ANN order.
- Success: **primary = context_precision (rerank on) beats Slice-2 baseline** (0.810 llama / 0.843 gemma); secondary = MRR ≥ 0.948, recall@8 not regressed; rerank-off must reproduce the baseline exactly. A CI-aware negative result is a valid, honestly-reported outcome.
- Git: `git add` only the paths named per task; never stage unrelated changes (`CLAUDE.md`, `.axon/`, `docs/agents/`, `rpgm`, `*/bin/`). Commit `--no-gpg-sign` (re-signed at the end; last signed tip is `8b2395c`).
- Reference: spec `docs/superpowers/specs/2026-07-02-slice3-reranker-design.md`; baseline `docs/eval-baseline.md`; domain `SourceChunk(chunkId, text, pageNumber, score, rulebookId)`.

---

## File structure

- `app/.../config/RerankProperties.java` (new) — `rpg.rerank` config record.
- `app/.../application/port/RerankPort.java` (new) — the port.
- `app/.../adapter/outbound/TeiRerankAdapter.java` (new) — TEI HTTP adapter.
- `app/.../application/RetrievalService.java` (new) — search + optional rerank; the single retrieval path.
- `app/.../application/QueryUseCase.java` (modify) — use `RetrievalService`.
- `app/src/main/resources/application.yml` (modify) — `rpg.rerank` block.
- `app/src/integrationTest/.../eval/RetrievalBaselineEval.java` (modify) — route through `RetrievalService`.
- `app/build.gradle` (modify) — `eval` task passes rerank system properties when `-Prerank` is set.
- `docker-compose.yml` (modify) — TEI service.
- `docs/eval-baseline.md`, `docs/eval-results-matrix.md` (modify) — rerank on/off results.

---

### Task 1: RerankProperties config

**Files:**
- Create: `app/src/main/java/com/rpgmaster/app/config/RerankProperties.java`
- Create: `app/src/test/java/com/rpgmaster/app/config/RerankPropertiesTest.java`
- Modify: `app/src/main/resources/application.yml`

**Interfaces:**
- Produces: `RerankProperties(boolean enabled, int topN, String model, String baseUrl)` with accessors `enabled()`, `topN()`, `model()`, `baseUrl()`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rpgmaster/app/config/RerankPropertiesTest.java`:
```java
package com.rpgmaster.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RerankPropertiesTest {

    @Test
    @DisplayName("holds values when top-n is positive")
    void validValues() {
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", "http://localhost:8090");
        assertThat(props.enabled()).isTrue();
        assertThat(props.topN()).isEqualTo(30);
        assertThat(props.model()).isEqualTo("BAAI/bge-reranker-v2-m3");
        assertThat(props.baseUrl()).isEqualTo("http://localhost:8090");
    }

    @Test
    @DisplayName("rejects non-positive top-n")
    void rejectsBadTopN() {
        assertThatThrownBy(() -> new RerankProperties(true, 0, "m", "http://x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rpg.rerank.top-n");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.config.RerankPropertiesTest'`
Expected: FAIL — `RerankProperties` does not exist (compile error).

- [ ] **Step 3: Write the config record**

`app/src/main/java/com/rpgmaster/app/config/RerankProperties.java`:
```java
package com.rpgmaster.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reranking config. When {@code enabled}, the retrieval path fetches {@code topN}
 * candidates from the vector store and reorders them with a cross-encoder (TEI,
 * {@code model}) at {@code baseUrl}, keeping the caller's top-K.
 *
 * @param enabled whether reranking runs (off reproduces the vector-only baseline)
 * @param topN    candidates fetched before reranking (should be >= retrieval top-k)
 * @param model   reranker model id served by TEI
 * @param baseUrl TEI base URL (no trailing path)
 */
@ConfigurationProperties(prefix = "rpg.rerank")
public record RerankProperties(boolean enabled, int topN, String model, String baseUrl) {

    public RerankProperties {
        if (topN <= 0) {
            throw new IllegalArgumentException("rpg.rerank.top-n must be > 0, was " + topN);
        }
    }
}
```

- [ ] **Step 4: Add the config block to `application.yml`**

In `app/src/main/resources/application.yml`, under the existing `rpg:` block (after `retrieval:`), add:
```yaml
  # Cross-encoder reranking (Slice 3). Off by default; enabled for the A/B eval.
  rerank:
    enabled: false
    top-n: 30
    model: BAAI/bge-reranker-v2-m3
    base-url: http://localhost:8090
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.config.RerankPropertiesTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rpgmaster/app/config/RerankProperties.java \
        app/src/test/java/com/rpgmaster/app/config/RerankPropertiesTest.java \
        app/src/main/resources/application.yml
git commit --no-gpg-sign -m "feat: rpg.rerank config properties"
```

---

### Task 2: RerankPort + TeiRerankAdapter

**Files:**
- Create: `app/src/main/java/com/rpgmaster/app/application/port/RerankPort.java`
- Create: `app/src/main/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapter.java`
- Create: `app/src/test/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapterTest.java`

**Interfaces:**
- Consumes: `RerankProperties` (Task 1); `com.rpgmaster.domain.SourceChunk(chunkId, text, pageNumber, score, rulebookId)`.
- Produces: `RerankPort.rerank(String query, List<SourceChunk> candidates, int topK) -> List<SourceChunk>` (reordered, truncated to topK, `score` = reranker score).

- [ ] **Step 1: Write the port**

`app/src/main/java/com/rpgmaster/app/application/port/RerankPort.java`:
```java
package com.rpgmaster.app.application.port;

import java.util.List;

import com.rpgmaster.domain.SourceChunk;

/**
 * Port for cross-encoder reranking. Adapter: {@link com.rpgmaster.app.adapter.outbound.TeiRerankAdapter}.
 */
public interface RerankPort {

    /**
     * Reorders {@code candidates} by cross-encoder relevance to {@code query} and
     * returns the top-{@code topK}. Returned chunks carry the reranker score in
     * {@link SourceChunk#score()}. Returns an empty list if {@code candidates} is empty.
     *
     * @param query      the user question
     * @param candidates ANN-retrieved chunks to reorder
     * @param topK       maximum number of chunks to return after reranking
     * @return reranked chunks, highest relevance first, at most {@code topK}
     */
    List<SourceChunk> rerank(String query, List<SourceChunk> candidates, int topK);
}
```

- [ ] **Step 2: Write the failing adapter test (Spring MockRestServiceServer, no Docker)**

`app/src/test/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapterTest.java`:
```java
package com.rpgmaster.app.adapter.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

class TeiRerankAdapterTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private TeiRerankAdapter adapter;

    private static SourceChunk chunk(String id, String text) {
        return new SourceChunk(id, text, 1, 0.5f, "dnd-5e-phb");
    }

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://tei.test");
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", "http://tei.test");
        adapter = new TeiRerankAdapter(builder, props);
    }

    @Test
    @DisplayName("reorders candidates by TEI score and truncates to topK")
    void reordersAndTruncates() {
        // TEI returns index->score; candidate 2 is most relevant, then 0, then 1.
        server.expect(requestTo("http://tei.test/rerank"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(
                      "[{\"index\":2,\"score\":0.9},{\"index\":0,\"score\":0.5},{\"index\":1,\"score\":0.1}]",
                      MediaType.APPLICATION_JSON));

        var candidates = List.of(chunk("a", "alpha"), chunk("b", "bravo"), chunk("c", "charlie"));
        var result = adapter.rerank("q", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo("c");   // index 2, score 0.9
        assertThat(result.get(0).score()).isEqualTo(0.9f);
        assertThat(result.get(1).chunkId()).isEqualTo("a");   // index 0, score 0.5
        server.verify();
    }

    @Test
    @DisplayName("returns empty for empty candidates without calling TEI")
    void emptyCandidates() {
        var result = adapter.rerank("q", List.of(), 5);
        assertThat(result).isEmpty();
        server.verify(); // no request expected
    }

    @Test
    @DisplayName("fails loud on TEI error (no silent fallback)")
    void failsLoudOnError() {
        server.expect(requestTo("http://tei.test/rerank")).andRespond(withServerError());
        var candidates = List.of(chunk("a", "alpha"));
        assertThatThrownBy(() -> adapter.rerank("q", candidates, 5))
                .isInstanceOf(RuntimeException.class);
        server.verify();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.adapter.outbound.TeiRerankAdapterTest'`
Expected: FAIL — `TeiRerankAdapter` does not exist (compile error).

- [ ] **Step 4: Write the adapter**

`app/src/main/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapter.java`:
```java
package com.rpgmaster.app.adapter.outbound;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

/**
 * Reranker adapter backed by HuggingFace TEI ({@code POST /rerank}) serving a
 * cross-encoder ({@code bge-reranker-v2-m3}). TEI returns [{index, score}, …];
 * this remaps indices back to the original {@link SourceChunk}s, overwrites the
 * score with the reranker score, and keeps the top-K. Fails loud on TEI error.
 */
@Component
public class TeiRerankAdapter implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(TeiRerankAdapter.class);

    private final RestClient restClient;

    public TeiRerankAdapter(RestClient.Builder builder, RerankProperties props) {
        this.restClient = builder.baseUrl(props.baseUrl()).build();
    }

    @Override
    public List<SourceChunk> rerank(String query, List<SourceChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        var texts = candidates.stream().map(SourceChunk::text).toList();
        RerankResult[] results = restClient.post()
                .uri("/rerank")
                .body(Map.of("query", query, "texts", texts))
                .retrieve()
                .body(RerankResult[].class);
        if (results == null) {
            throw new IllegalStateException("TEI /rerank returned no body");
        }
        log.debug("Reranked {} candidates -> keeping top {}", candidates.size(), topK);
        return Arrays.stream(results)
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .limit(topK)
                .map(r -> {
                    SourceChunk c = candidates.get(r.index());
                    return new SourceChunk(c.chunkId(), c.text(), c.pageNumber(), (float) r.score(), c.rulebookId());
                })
                .toList();
    }

    /** TEI /rerank response element. */
    record RerankResult(int index, double score) {}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.adapter.outbound.TeiRerankAdapterTest'`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/rpgmaster/app/application/port/RerankPort.java \
        app/src/main/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapter.java \
        app/src/test/java/com/rpgmaster/app/adapter/outbound/TeiRerankAdapterTest.java
git commit --no-gpg-sign -m "feat: RerankPort + TEI cross-encoder adapter"
```

---

### Task 3: RetrievalService + QueryUseCase refactor

**Files:**
- Create: `app/src/main/java/com/rpgmaster/app/application/RetrievalService.java`
- Create: `app/src/test/java/com/rpgmaster/app/application/RetrievalServiceTest.java`
- Modify: `app/src/main/java/com/rpgmaster/app/application/QueryUseCase.java`

**Interfaces:**
- Consumes: `EmbeddingPort.embed(String)`, `VectorStorePort.search(String, List<Float>, int, float)`, `RerankPort.rerank(String, List<SourceChunk>, int)` (Task 2), `RetrievalProperties`, `RerankProperties` (Task 1).
- Produces: `RetrievalService.retrieve(String rulebookId, String question, float threshold, int topK) -> List<SourceChunk>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/rpgmaster/app/application/RetrievalServiceTest.java`:
```java
package com.rpgmaster.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.SourceChunk;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock EmbeddingPort embeddingPort;
    @Mock VectorStorePort vectorStorePort;
    @Mock RerankPort rerankPort;

    private static SourceChunk chunk(String id) {
        return new SourceChunk(id, id + "-text", 1, 0.5f, "dnd-5e-phb");
    }

    private RetrievalService service(boolean rerankEnabled) {
        var retrieval = new RetrievalProperties(8, 0.3f);
        var rerank = new RerankProperties(rerankEnabled, 30, "m", "http://tei");
        return new RetrievalService(embeddingPort, vectorStorePort, rerankPort, retrieval, rerank);
    }

    @Test
    @DisplayName("rerank disabled: searches at topK and does not call the reranker")
    void rerankDisabled() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        when(vectorStorePort.search("rb", List.of(0.1f), 8, 0.3f)).thenReturn(List.of(chunk("a")));

        var result = service(false).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("a");
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("rerank enabled: searches at topN then reranks to topK")
    void rerankEnabled() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        var candidates = List.of(chunk("a"), chunk("b"), chunk("c"));
        when(vectorStorePort.search("rb", List.of(0.1f), 30, 0.3f)).thenReturn(candidates);
        when(rerankPort.rerank("q", candidates, 8)).thenReturn(List.of(chunk("c"), chunk("a")));

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).extracting(SourceChunk::chunkId).containsExactly("c", "a");
        verify(vectorStorePort).search("rb", List.of(0.1f), 30, 0.3f);
    }

    @Test
    @DisplayName("rerank enabled but no candidates: returns empty, no rerank call")
    void rerankEnabledEmpty() {
        when(embeddingPort.embed("q")).thenReturn(List.of(0.1f));
        when(vectorStorePort.search(eq("rb"), any(), eq(30), eq(0.3f))).thenReturn(List.of());

        var result = service(true).retrieve("rb", "q", 0.3f, 8);

        assertThat(result).isEmpty();
        verify(rerankPort, never()).rerank(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.application.RetrievalServiceTest'`
Expected: FAIL — `RetrievalService` does not exist.

- [ ] **Step 3: Write the service**

`app/src/main/java/com/rpgmaster/app/application/RetrievalService.java`:
```java
package com.rpgmaster.app.application;

import java.util.List;

import org.springframework.stereotype.Service;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.SourceChunk;

/**
 * The single retrieval path shared by the query pipeline and the eval harness.
 * Embeds the question, searches the vector store, and — when reranking is enabled
 * — fetches {@code topN} candidates and reorders them with the cross-encoder,
 * keeping the caller's {@code topK}. With reranking disabled it searches directly
 * at {@code topK}, reproducing the vector-only baseline.
 */
@Service
public class RetrievalService {

    private final EmbeddingPort embeddingPort;
    private final VectorStorePort vectorStorePort;
    private final RerankPort rerankPort;
    private final RetrievalProperties retrieval;
    private final RerankProperties rerank;

    public RetrievalService(EmbeddingPort embeddingPort,
                            VectorStorePort vectorStorePort,
                            RerankPort rerankPort,
                            RetrievalProperties retrieval,
                            RerankProperties rerank) {
        this.embeddingPort = embeddingPort;
        this.vectorStorePort = vectorStorePort;
        this.rerankPort = rerankPort;
        this.retrieval = retrieval;
        this.rerank = rerank;
    }

    /**
     * Retrieves up to {@code topK} chunks for the question.
     *
     * @param rulebookId namespace filter; null = all rulebooks
     * @param question   the user question
     * @param threshold  minimum cosine similarity for the vector search
     * @param topK       chunks to return (query path: production top-k; eval: sweep maxK)
     */
    public List<SourceChunk> retrieve(String rulebookId, String question, float threshold, int topK) {
        var vector = embeddingPort.embed(question);
        if (!rerank.enabled()) {
            return vectorStorePort.search(rulebookId, vector, topK, threshold);
        }
        int topN = Math.max(rerank.topN(), topK);
        var candidates = vectorStorePort.search(rulebookId, vector, topN, threshold);
        if (candidates.isEmpty()) {
            return List.of();
        }
        return rerankPort.rerank(question, candidates, topK);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.application.RetrievalServiceTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Refactor `QueryUseCase` to use `RetrievalService`**

In `app/src/main/java/com/rpgmaster/app/application/QueryUseCase.java`:
- Remove fields `embeddingPort` and `vectorStorePort` and their constructor params + imports (`EmbeddingPort`, `VectorStorePort`). Add a `private final RetrievalService retrievalService;` field + constructor param.
- Replace, in `query(...)`:
  ```java
  var queryVector = embeddingPort.embed(request.question());
  var sources = vectorStorePort.search(
          request.rulebookId(), queryVector, request.topK(), request.similarityThreshold()
  );
  ```
  with:
  ```java
  var sources = retrievalService.retrieve(
          request.rulebookId(), request.question(), request.similarityThreshold(), request.topK()
  );
  ```
- Replace the identical block in `queryStream(...)` the same way.
- Leave the `log.info("Retrieved {} chunks from Qdrant", sources.size())` and everything else unchanged.

- [ ] **Step 6: Run the query unit + integration tests to verify the refactor is behavior-preserving**

Run: `./gradlew :app:test --tests 'com.rpgmaster.app.application.*'`
Expected: PASS. If a `QueryUseCaseTest` constructs `QueryUseCase` directly, update its constructor call to pass a (mocked) `RetrievalService` instead of `EmbeddingPort`/`VectorStorePort` — mirror the existing test's stubbing so it still asserts the same behavior. Show the updated constructor call in your commit.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rpgmaster/app/application/RetrievalService.java \
        app/src/test/java/com/rpgmaster/app/application/RetrievalServiceTest.java \
        app/src/main/java/com/rpgmaster/app/application/QueryUseCase.java
# include QueryUseCaseTest only if you had to modify it:
# git add app/src/test/java/com/rpgmaster/app/application/QueryUseCaseTest.java
git commit --no-gpg-sign -m "refactor: shared RetrievalService (search + optional rerank)"
```

---

### Task 4: TEI infra + integration test

**Files:**
- Modify: `docker-compose.yml`
- Create: `app/src/integrationTest/java/com/rpgmaster/app/integration/TeiRerankIntegrationTest.java`

**Interfaces:**
- Consumes: `TeiRerankAdapter` (Task 2), `RerankProperties` (Task 1).

- [ ] **Step 1: Add the TEI service to `docker-compose.yml`**

Add under `services:`:
```yaml
  tei-reranker:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-1.5
    command: ["--model-id", "BAAI/bge-reranker-v2-m3"]
    ports:
      - "8090:80"
    volumes:
      - tei_data:/data
```
And add `tei_data:` under the top-level `volumes:` block. (TEI listens on container port 80; downloads the model to `/data` on first start.)

- [ ] **Step 2: Write the integration test (real TEI via Testcontainers)**

`app/src/integrationTest/java/com/rpgmaster/app/integration/TeiRerankIntegrationTest.java`:
```java
package com.rpgmaster.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.rpgmaster.app.adapter.outbound.TeiRerankAdapter;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

import org.springframework.web.client.RestClient;

/**
 * Verifies the reranker against a REAL TEI container serving bge-reranker-v2-m3.
 * Slow on first run (model download ~2GB); integration tier only, not CI-critical.
 * Run: ./gradlew :app:integrationTest --tests '*TeiRerankIntegrationTest' (requires Docker)
 */
@Testcontainers(disabledWithoutDocker = true)
class TeiRerankIntegrationTest {

    @Container
    static GenericContainer<?> tei = new GenericContainer<>("ghcr.io/huggingface/text-embeddings-inference:cpu-1.5")
            .withCommand("--model-id", "BAAI/bge-reranker-v2-m3")
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/health").forPort(80).withStartupTimeout(Duration.ofMinutes(10)));

    private static SourceChunk chunk(String id, String text) {
        return new SourceChunk(id, text, 1, 0.5f, "dnd-5e-phb");
    }

    @Test
    @DisplayName("real TEI ranks the topically relevant chunk first")
    void ranksRelevantFirst() {
        String baseUrl = "http://" + tei.getHost() + ":" + tei.getMappedPort(80);
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", baseUrl);
        var adapter = new TeiRerankAdapter(RestClient.builder(), props);

        var candidates = List.of(
                chunk("weather", "The weather today is sunny and warm."),
                chunk("fireball", "Fireball: a bright streak flashes to a point and blossoms into flame, 8d6 fire damage."),
                chunk("cooking", "To bake bread, mix flour, water, yeast and salt."));

        var result = adapter.rerank("How much damage does a fireball deal?", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo("fireball");
    }
}
```

- [ ] **Step 3: Run the integration test**

Run (Docker required): `./gradlew :app:integrationTest --tests 'com.rpgmaster.app.integration.TeiRerankIntegrationTest'`
Expected: PASS (first run pulls the TEI image + downloads the model — several minutes). This validates the real TEI `/rerank` request/response shape the adapter assumes. If the response shape differs, fix `TeiRerankAdapter.RerankResult` mapping and re-run this test + `TeiRerankAdapterTest`.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml \
        app/src/integrationTest/java/com/rpgmaster/app/integration/TeiRerankIntegrationTest.java
git commit --no-gpg-sign -m "test: TEI reranker service + integration test"
```

---

### Task 5: Route eval harness through RetrievalService + record Track A (on vs off)

**Files:**
- Modify: `app/src/integrationTest/java/com/rpgmaster/app/eval/RetrievalBaselineEval.java`
- Modify: `app/build.gradle`
- Modify: `docs/eval-baseline.md`, `docs/eval-results-matrix.md`

**Interfaces:**
- Consumes: `RetrievalService.retrieve(String, String, float, int)` (Task 3).

- [ ] **Step 1: Route the eval through `RetrievalService`**

In `app/src/integrationTest/java/com/rpgmaster/app/eval/RetrievalBaselineEval.java`:
- Replace the `@Autowired EmbeddingPort embeddingPort;` and `@Autowired VectorStorePort vectorStorePort;` fields with `@Autowired RetrievalService retrievalService;` (add import `com.rpgmaster.app.application.RetrievalService`; remove the now-unused `EmbeddingPort`/`VectorStorePort` imports).
- Replace, inside the case loop:
  ```java
  var vector = embeddingPort.embed(c.question());
  String rulebook = c.relevantPages().get(0).rulebookId();
  List<SourceChunk> hits = vectorStorePort.search(rulebook, vector, maxK, THRESHOLD);
  ```
  with:
  ```java
  String rulebook = c.relevantPages().get(0).rulebookId();
  List<SourceChunk> hits = retrievalService.retrieve(rulebook, c.question(), THRESHOLD, maxK);
  ```
  (`maxK` is already computed as `Arrays.stream(K_SWEEP).max()`. Passing `topK=maxK` means the reranked list has ≥ maxK entries for the sweep, per the spec.)
- Add one line to the report header so on/off runs are distinguishable — after the `Cases:`/`threshold:` line, append the rerank state:
  ```java
  md.append("Rerank: ").append(System.getProperty("rpg.rerank.enabled", "false")).append("\n\n");
  ```

- [ ] **Step 2: Make the `eval` gradle task toggle rerank via `-Prerank`**

In `app/build.gradle`, find `tasks.register('eval', Test) { … }` and add inside its configuration block:
```groovy
    if (project.hasProperty('rerank')) {
        systemProperty 'rpg.rerank.enabled', 'true'
        if (project.hasProperty('topN')) {
            systemProperty 'rpg.rerank.top-n', project.property('topN')
        }
    }
```
So `./gradlew :app:eval` runs rerank-off and `./gradlew :app:eval -Prerank` runs rerank-on (optionally `-PtopN=20`).

- [ ] **Step 3: Bring up the stack and run rerank-OFF (must reproduce the baseline)**

Prereqs (all with sandbox disabled): Qdrant + Ollama up (corpus ingested, `bge-m3`), and — for the on run only — the TEI container up: `docker compose up -d tei-reranker` then wait for `curl -s http://localhost:8090/health`.
Run: `./gradlew :app:eval`
Expected: `BUILD SUCCESSFUL`; report shows `Rerank: false`, recall@k=1.000, MRR≈0.948 (reproduces `docs/eval-baseline.md`). If it does not reproduce, STOP — the refactor changed retrieval behavior; fix before proceeding.

- [ ] **Step 4: Run rerank-ON (with TEI up), optionally sweep top-n**

Run: `./gradlew :app:eval -Prerank` (and e.g. `-Prerank -PtopN=20`, `-PtopN=50` for a small sweep).
Expected: `BUILD SUCCESSFUL`; report shows `Rerank: true` and the reranked recall@k/MRR. Note the new `eval/reports/retrieval-<millis>.md` filenames for both states.

- [ ] **Step 5: Record Track A on/off in the docs**

In `docs/eval-baseline.md` (Retrieval section) and `docs/eval-results-matrix.md`, add a rerank-off vs rerank-on comparison table using the exact numbers from Steps 3–4 (and the top-n sweep). State whether MRR/recall@8 moved and at which top-n. No invented numbers.

- [ ] **Step 6: Commit**

```bash
git add app/src/integrationTest/java/com/rpgmaster/app/eval/RetrievalBaselineEval.java \
        app/build.gradle docs/eval-baseline.md docs/eval-results-matrix.md \
        eval/reports/retrieval-*.md
git commit --no-gpg-sign -m "test: eval via RetrievalService + Track A rerank on/off"
```
(Stage only the NEW report files from this task — check `git status` and add them by name; do not stage pre-existing reports.)

---

### Task 6: Track B — context_precision with rerank on

**Files:**
- Modify: `docs/eval-baseline.md`, `docs/eval-results-matrix.md`

**Interfaces:**
- Consumes: the running app (rerank on), `infra/scripts/gnomon_batch_two_judges.py` (Slice 2), TEI up.

- [ ] **Step 1: Boot the app with rerank enabled**

Prereqs (sandbox disabled): Qdrant + Ollama + TEI (`docker compose up -d tei-reranker`, wait `curl http://localhost:8090/health`). The batch script's generator is `llama3.2:3b` (Slice-2 method); the desktop judges (`llama3.1:8b`, `gemma4:e4b`) must be reachable at `http://100.78.123.92:11434`.
Boot (nohup, generator override + rerank on; profile `local,api` → port 8082):
```bash
nohup env SPRING_PROFILES_ACTIVE=local,api \
  SPRING_AI_OLLAMA_CHAT_MODEL=llama3.2:3b \
  RPG_RERANK_ENABLED=true \
  ./gradlew :app:bootRun > /tmp/bootrun-rerank.log 2>&1 < /dev/null &
```
Wait for health: `curl -s http://localhost:8082/v1/models`. Confirm rerank is active (a query should hit TEI — check the app log for a rerank debug line, or that TEI received a request).

- [ ] **Step 2: Run the two-judge batch (generate-once, judge llama3.1:8b + gemma4:e4b)**

Run (sandbox disabled, detached — it is long): 
```bash
nohup python3 -u infra/scripts/gnomon_batch_two_judges.py > /tmp/batch-rerank.log 2>&1 < /dev/null &
```
Poll `/tmp/batch-rerank.log` for the `[RESULT]` lines and the final `FINAL {json}`. Record context_precision + 95% CI for each judge.

- [ ] **Step 3: Compare against the Slice-2 baseline and record**

In `docs/eval-baseline.md` (Track B section) and `docs/eval-results-matrix.md`, add a rerank-on row/column beside the Slice-2 baseline (llama 0.810 / gemma 0.843). State, CI-aware, whether context_precision improved, held, or regressed under each judge. A negative/flat result is a valid outcome — report it honestly. Note generator (llama3.2:3b), rerank top-n, and that this is a non-CI snapshot.

- [ ] **Step 4: Stop the app and commit**

Stop the bootRun (`pkill -f bootRun`; stop TEI with `docker compose stop tei-reranker` if desired).
```bash
git add docs/eval-baseline.md docs/eval-results-matrix.md
git commit --no-gpg-sign -m "test: Track B context_precision with rerank on (two-judge)"
```

---

## Finalization (after all tasks)

- [ ] **Re-sign the range outside the sandbox** (user runs via `!`; last signed tip `8b2395c`):
```bash
git rebase --autostash 8b2395c --exec "git commit --amend --no-edit -S"
```
- [ ] **Confirm** each commit staged only its task's paths (`git show --stat`), and that `*/bin/` build output and unrelated working-tree files were never committed.

---

## Self-review

**Spec coverage:**
- RerankPort + TEI adapter → Task 2. ✓
- RerankProperties / `rpg.rerank` config + toggle → Task 1 (+ Task 5 gradle toggle, Task 6 env toggle). ✓
- Shared RetrievalService used by query path + eval → Task 3 (QueryUseCase) + Task 5 (eval). ✓
- topN≫topK, eval requests reranked list at maxK → Task 3 (`Math.max(topN, topK)`) + Task 5 (`topK=maxK`). ✓
- TEI infra (colima) + Testcontainers → Task 4. ✓
- Track A on vs off + top-n sweep → Task 5. ✓
- Track B context_precision rerank-on → Task 6. ✓
- Fail-loud on TEI error → Task 2 (test `failsLoudOnError`, adapter throws). ✓
- Rerank-off reproduces baseline → Task 5 Step 3 gate. ✓
- Success criteria incl. honest negative result → Tasks 5–6 recording steps. ✓

**Placeholder scan:** no TBD/TODO; every code step has full code; the eval/GNOMON numbers in Tasks 5–6 are runtime-measured and explicitly "record from the run" (unknowable until executed) — acceptable.

**Type consistency:** `RerankPort.rerank(String, List<SourceChunk>, int)`, `RetrievalService.retrieve(String, String, float, int)`, `RerankProperties(boolean, int, String, String)`, `TeiRerankAdapter(RestClient.Builder, RerankProperties)` — used identically across Tasks 1–5. `SourceChunk` constructor order `(chunkId, text, pageNumber, score, rulebookId)` matches the domain record. ✓
