# ADR-002: Ollama For Local Models

## Status

Accepted

## Context

Step 1 needs a repeatable local development loop without API keys, per-token cost, or cloud dependency. The project also wants a clear path to a different production model later.

## Decision

Use Ollama locally for both embeddings and chat generation, abstracted behind Spring AI adapters and application ports.

## Consequences

- Local development is cheap and reproducible.
- Model iteration is fast, including swaps such as `llama3.x` to `qwen2.5:7b`.
- Hardware limits remain a practical constraint for local quality.
- A production model swap remains possible without changing the use cases.
