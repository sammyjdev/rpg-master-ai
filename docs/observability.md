# Observability — Phase 2

> Operator and contributor guide. Pairs with [ADR-012](adr/ADR-012-observability-contract.md),
> which is the load-bearing contract; this document is the readable walkthrough.

The Phase 2 observability stack has three layers — each useful on its own,
all consistent with each other.

| Layer              | Sink                                                | Use case                                                |
| ------------------ | --------------------------------------------------- | ------------------------------------------------------- |
| Live metrics       | Prometheus scrape → Grafana dashboard               | Real-time SRE-style ops: P99 latency, throughput, cost  |
| Per-query audit    | One JSON line per query on `rpg.query.audit` logger | Offline replay: eval harness, regression detection      |
| Prompt versioning  | `# version: vX.Y` header in every `.st` template    | Correlate any answer or eval report back to the prompt  |

## 1. Live metrics — Prometheus + Grafana

### What gets emitted

`RagMetrics` is the single owner of every meter. The contract:

| Metric                            | Type    | Where it fires                    |
| --------------------------------- | ------- | --------------------------------- |
| `rpg.query.latency`               | Timer   | End of every `QueryUseCase.query` and at terminate of `queryStream` |
| `rpg.query.tokens_used`           | Counter | Same site as latency — split by `direction=input|output` |
| `rpg.query.cost_usd`              | Counter | Same site — derived from token count × `rpg.metrics.cost` pricing |
| `rpg.ingestion.chunks_processed`  | Counter | End of `IngestionUseCase.ingest`, tagged with `status` |
| `rpg.embedding.latency`           | Timer   | Around the `embedBatch` call in `IngestionUseCase`, bucketed by batch size |

See [ADR-012 § 1](adr/ADR-012-observability-contract.md#1-metric-names-and-tags)
for the exact tag specification.

### Running it locally

```bash
# Start the observability stack alongside the existing data services
docker compose up -d qdrant postgres prometheus grafana

# Start the app on the host so it scrapes from host.docker.internal:8082
./rpgm start
```

Endpoints:

- App metrics: <http://localhost:8082/actuator/prometheus>
- Prometheus UI: <http://localhost:9090>
- Prometheus targets (verify the scrape is green): <http://localhost:9090/targets>
- Grafana: <http://localhost:3001> — anonymous Viewer enabled, `admin/admin` to edit

The Grafana datasource and the "RPG Master AI" folder are
auto-provisioned from `infra/observability/grafana/provisioning/`. Drop
new dashboard JSON files into `infra/observability/grafana/dashboards/`
and Grafana picks them up on the next 30s sweep.

### Reading the dashboard

`infra/observability/grafana/dashboards/rag.json` ships with these panels:

| Row | Panel                                  | What it tells you                                                   |
| --- | -------------------------------------- | ------------------------------------------------------------------- |
| 1   | Query latency P50 / P95 / P99          | Trendline over the last 30 min. Look for P99 spikes after prompt changes. |
| 1   | Total queries (stat)                   | Cumulative count — sanity check that traffic is flowing.            |
| 1   | Estimated cost USD (stat)              | Sums `rpg_query_cost_usd_total`. Zero on local Ollama.              |
| 1   | Median + P99 latency (stat)            | Headline numbers for the README badge.                              |
| 2   | Token throughput in/out per minute     | Input vs output rate. Watch the gap when prompts get longer.        |
| 2   | Ingestion throughput by status         | Green = success rate, red = failures. Should be ~zero red.          |
| 3   | Embedding latency P95 by batch size    | One series per bucket. Larger batches must amortise per-chunk cost. |

The `rulebook` template variable at the top scopes every panel to one
rulebook ID — drives Phase 4's multi-rulebook isolation story.

### Adding a new meter

1. Add a method to `RagMetrics` — never call the `MeterRegistry` directly
   from business code. Keeping all meters in one class is what guarantees
   the contract.
2. Add a `public static final String` constant for the metric name and any
   new tag keys. Tests pin these so a typo breaks the build, not the
   dashboard.
3. Add a unit test in `RagMetricsTest` that asserts the meter is registered
   with the right tags and increments correctly. Use `SimpleMeterRegistry`
   so the test stays in-memory and fast.
4. If the new meter is a Timer that needs percentile queries in Grafana,
   add it to `management.metrics.distribution.percentiles-histogram` in
   `application.yml`. Otherwise PromQL `histogram_quantile` returns NaN.

## 2. Per-query audit log

Every query — blocking or stream — emits a single JSON line on the SLF4J
logger named `rpg.query.audit`. Example:

```json
{"prompt_version":"v1.0","rulebook_id":"dnd-5e-phb","mode":"blocking","top_k":8,"similarity_threshold":0.3,"retrieval_count":8,"prompt_tokens":412,"completion_tokens":87,"latency_ms":1834}
```

### Why it exists separately from metrics

Prometheus is great for "what's happening now" but bad for "what did the
LLM answer to question #14 in the golden Q&A set yesterday." The audit log
is replayable, grep-able, and committable as eval evidence. The eval
harness in Phase 2 reads these lines directly.

### Routing it to its own file

In production-style deployments, route the channel to a dedicated appender
so audit lines never mix with operational logs. Logback example:

```xml
<appender name="audit" class="ch.qos.logback.core.FileAppender">
  <file>/var/log/rpg-master/audit.jsonl</file>
  <encoder><pattern>%msg%n</pattern></encoder>
</appender>

<logger name="rpg.query.audit" additivity="false" level="INFO">
  <appender-ref ref="audit"/>
</logger>
```

The `additivity="false"` and the bare `%msg%n` pattern matter — they
guarantee the file is a clean JSON Lines stream with no timestamp or thread
prefix, ready to feed `jq`, DuckDB, or a streaming pipeline.

### Field stability

`QueryAuditEvent` is a Java record with `@JsonPropertyOrder` — field
order, snake-cased names, and JSON shape are stable across runs. Adding a
new field is allowed (it goes at the end via `@JsonInclude(NON_NULL)`);
renaming or removing a field requires a new ADR.

## 3. Prompt versioning

Every prompt template under `app/src/main/resources/prompts/` starts with
a comment line:

```text
# version: v1.0
You are a rules expert for RPG rulebooks. …
```

`PromptConfig.parse` extracts the version, strips the line from the runtime
body (the model never sees it), and exposes the value as the
`ragPromptVersion` Spring bean. `QueryUseCase` injects it and writes it
into every audit event.

### Bumping a template

1. Edit the template body.
2. Bump the version line (`v1.0` → `v1.1`).
3. Re-run the eval harness — its diff against the previous baseline report
   tells you whether the bump improved or regressed retrieval / answer
   quality before you merge.

Templates that forget the version header are tagged `"unversioned"` in the
audit log. The eval harness treats `"unversioned"` as a hard failure so the
mistake is caught the first time it ships.

## 4. Cost model

`rpg.metrics.cost` controls the `rpg.query.cost_usd` counter:

```yaml
rpg:
  metrics:
    cost:
      prompt-usd-per-1k: 0.0
      completion-usd-per-1k: 0.0
```

Defaults are `0.0` — local Ollama is free, so the counter is correct at
zero. For Phase 3 (NVIDIA NIM free tier) it stays zero. For Phase 5
(Bedrock) the per-profile yml overrides the values from the provider
pricing page; no code changes needed.

The counter is only registered when computed cost is > 0 so Prometheus
isn't scraping a perpetual-zero series during local dev.

## 5. Troubleshooting

| Symptom                                          | Likely cause                                     |
| ------------------------------------------------ | ------------------------------------------------ |
| Grafana panel shows "No data"                    | Either no traffic yet, or Prometheus scrape is down — check <http://localhost:9090/targets> |
| `histogram_quantile` returns NaN                 | Percentile histograms aren't enabled for that Timer in `application.yml` |
| Cost counter never appears in Prometheus         | Pricing is zero (expected) — cost > 0 first call registers it |
| Audit lines all have `prompt_version=unversioned`| The `.st` file is missing the `# version: vX.Y` header |
| Token counts are zero on streaming queries       | Spring AI streaming usage metadata isn't surfaced yet — Phase 3 work |
