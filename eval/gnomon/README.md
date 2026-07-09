# GNOMON answer-quality guardrail

Measures `faithfulness` + `context_precision` (each with a 95% CI) of
RPG_MASTER's answers, via GNOMON's LLM judge scoring responses from the live
OpenAI-compatible endpoint (`/v1/chat/completions`, model `all-rulebooks`).

GNOMON lives at `~/dev/gnomon-eval`. This directory only holds RPG_MASTER's
dataset + config for it.

## Response contract — verified

GNOMON's `OpenAICompatTarget` requires the target's chat/completions response
to include a top-level `contexts` field (list of retrieved context strings)
and `usage.total_tokens`, or it fails closed with `IncompleteResponseError`
(`~/dev/gnomon-eval/src/gnomon/targets/openai_compat.py`, VAL-03).

`OpenAiCompatibleController` (`app/src/main/java/com/rpgmaster/app/adapter/inbound/rest/OpenAiCompatibleController.java`)
returns both fields on the non-streaming response: `contexts` (the retrieved
source chunk texts) and `usage.total_tokens` (via the `OpenAiUsage` record).
The guardrail is runnable against a live RPG_MASTER instance as-is.

## Run

1. Start RPG_MASTER (corpus ingested): `./gradlew :app:bootRun` (profile
   `local`; default port 8080, per `application.yml`).
2. Regenerate the dataset if the golden set changed:
   `./gradlew :app:integrationTest --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'`
   — writes `eval/gnomon/dataset.json`.
3. Make sure the judge model is pulled: `ollama pull qwen2.5:7b` (or edit
   `config.toml`'s `[judge]` block to point at a different local model).
4. Install GNOMON once: `cd ~/dev/gnomon-eval && pip install -e ".[dev]"`.
5. From **this directory** (`eval/gnomon/` in RPG_MASTER) — `dataset_path` in
   `config.toml` is relative to the invocation CWD:
   ```bash
   cd eval/gnomon
   gnomon --config config.toml
   ```
6. Copy the reported `faithfulness` / `context_precision` + CIs into
   `docs/eval-baseline.md` (Task 4).

The gate thresholds in `config.toml` are `0.0` (record-only) for this first
wiring — the run will report metrics but never fail the exit code. Set real
floors once a baseline is recorded.
