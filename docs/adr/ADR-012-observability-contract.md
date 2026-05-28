# ADR-012: Observability Contract

## Status

Accepted

## Context

Phase 2 turns the RAG loop from "feels right" into "measurable". For that we
need two things that downstream tooling can rely on without re-reading the
source:

1. A **stable set of metric names and tags** so the Grafana dashboard, the
   eval harness, and any future cost/alerting work all see the same series.
2. A **structured per-query audit record** that an offline pipeline (today
   the eval harness; tomorrow a BigQuery sink or an evaluation notebook)
   can replay without scraping Prometheus.

Without an explicit contract, every new meter risks renaming an existing one
or quietly inflating Prometheus label cardinality, and every change to
prompts or retrieval risks producing audit lines that the eval harness can no
longer parse.

## Decision

### 1. Metric names and tags

The Micrometer facade `RagMetrics` exposes one public method per metric.
Renaming or re-tagging any of these is a breaking change and must come with
a new ADR.

| Metric                          | Type    | Tags                              |
| ------------------------------- | ------- | --------------------------------- |
| `rpg.query.latency`             | Timer   | `rulebook_id`, `model`            |
| `rpg.query.tokens_used`         | Counter | `rulebook_id`, `model`, `direction` (`input` \| `output`) |
| `rpg.query.cost_usd`            | Counter | `model` — only registered when computed cost > 0 |
| `rpg.ingestion.chunks_processed`| Counter | `rulebook_id`, `status` (`success` \| `failed` \| `partial`) |
| `rpg.embedding.latency`         | Timer   | `model`, `batch_size` (`1` \| `2-10` \| `11-100` \| `100+`) |

Conventions:

- `null` rulebook (cross-rulebook query) becomes the literal string `all`,
  not the string `"null"` — keeps label cardinality bounded.
- `batch_size` is bucketed, not raw — a single embedding call with 856
  chunks must not produce a unique label value.
- `direction` is exactly `input` or `output` — split from the original
  `tokensUsed` total so cost can be computed per direction.
- Percentile histograms are enabled for the two Timers
  (`management.metrics.distribution.percentiles-histogram`) so PromQL
  `histogram_quantile(0.99, …)` works in Grafana without recompiling.

### 2. Audit log JSON shape

Every RAG query — blocking or streaming — emits one line on the SLF4J logger
named `rpg.query.audit`. The JSON shape is fixed by the
`QueryAuditEvent` record and is field-order stable via `@JsonPropertyOrder`:

```json
{
  "prompt_version": "v1.0",
  "rulebook_id": "dnd-5e-phb",
  "mode": "blocking",
  "top_k": 8,
  "similarity_threshold": 0.3,
  "retrieval_count": 8,
  "prompt_tokens": 412,
  "completion_tokens": 87,
  "latency_ms": 1834
}
```

Conventions:

- `mode` is exactly `blocking` or `stream`. Streaming calls emit the line at
  flux termination with `prompt_tokens`/`completion_tokens` = 0 until Spring
  AI exposes streaming usage metadata (tracked in Phase 3).
- `prompt_version` comes from the `# version: vX.Y` header in
  `prompts/rag-system.st`. Templates that forget the header log
  `"unversioned"` — silent regression is unacceptable, so the eval harness
  treats `"unversioned"` as a hard failure.
- The same data is also emitted to the Prometheus meters above. JSON line
  for offline replay, meters for live dashboards. They must agree.

### 3. Prompt versioning

Every prompt template under `prompts/` starts with a comment line
`# version: vX.Y`. `PromptConfig.parse` extracts it, strips it from the
runtime body (the model never sees it), and exposes it as the
`ragPromptVersion` Spring bean.

Bumping a template body without bumping the version is forbidden — every
audit line and every eval report must be re-runnable from the recorded
version.

## Consequences

**Positive**

- Dashboards in `infra/observability/grafana/dashboards/` reference stable
  PromQL expressions; no recompile needed when a panel is added.
- The eval harness consumes audit lines without parsing prompts or
  re-running the model: just grep the log file.
- Cost meter starts at zero with local Ollama but the code path is exercised
  daily — when Phase 5 wires Bedrock pricing, no new instrumentation is
  needed.
- Label cardinality is bounded by design (no question text, no chunk IDs,
  bucketed batch size) — Prometheus storage stays flat.

**Negative**

- The fixed JSON shape adds a small migration burden when new fields are
  needed. Mitigation: add fields with `@JsonInclude(NON_NULL)` defaults so
  old consumers ignore them.
- Streaming queries can't report token usage until Spring AI exposes it;
  meanwhile streaming audits underreport `tokens_used` totals.
- The audit logger writes JSON inline as a plain SLF4J INFO line — easy to
  parse but mixed with normal logs. Operators wanting a clean stream route
  `rpg.query.audit` to its own appender (documented in
  `docs/observability.md`).

## References

- `app/src/main/java/com/rpgmaster/app/observability/RagMetrics.java`
- `app/src/main/java/com/rpgmaster/app/observability/QueryAuditEvent.java`
- `app/src/main/java/com/rpgmaster/app/observability/QueryAuditLogger.java`
- `app/src/main/java/com/rpgmaster/app/config/PromptConfig.java`
- `infra/observability/grafana/dashboards/rag.json`
- `docs/observability.md` — operator-facing walkthrough
