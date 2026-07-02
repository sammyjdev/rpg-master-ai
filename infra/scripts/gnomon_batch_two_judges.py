#!/usr/bin/env python3
"""Track B: generate-once, judge-twice context_precision snapshot.

GNOMON's own runner interleaves generate->judge per case and re-queries the
target on every invocation (in-memory cache only). That makes a two-judge
robustness comparison pay the slow local generation TWICE. This script instead:

  Phase 1 (generate): query the target once for all cases -> reuse the responses.
  Phase 2 (judge):    score the SAME responses with each judge model in turn.

Because `context_precision` depends only on (question, retrieved contexts) and
never on the generated answer, the throwaway generator can be small/fast and the
two judges see identical inputs -> a clean, cheap robustness comparison.

It imports GNOMON's own target/judge/aggregation (no fork of ~/dev/gnomon-eval),
so the metric math matches `gnomon --config`. Prints per-case progress (unlike
`gnomon --json`, which is silent until the end).

Run (app up on :8082, desktop Ollama reachable):
  python3 infra/scripts/gnomon_batch_two_judges.py
"""
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.expanduser("~/dev/gnomon-eval/src"))
from gnomon.dataset.loader import load_dataset          # noqa: E402
from gnomon.judge.cache import JudgeCache                # noqa: E402
from gnomon.judge.ollama import OllamaJudge              # noqa: E402
from gnomon.metrics.confidence import aggregate_metric   # noqa: E402
from gnomon.targets.openai_compat import OpenAICompatTarget  # noqa: E402

DATASET = "eval/gnomon/dataset.json"
TARGET_URL = os.environ.get("TARGET_URL", "http://localhost:8082/v1")
TARGET_MODEL = "all-rulebooks"
JUDGE_URL = os.environ.get("JUDGE_URL", "http://<judge-host>:11434")
JUDGES = ["llama3.1:8b", "gemma4:e4b"]
# JUDGE_RUNS and SEED intentionally mirror eval/gnomon/config.toml [eval]
# (judge_runs, seed). Keep them in sync so results stay comparable to a direct
# `gnomon --config config.toml` run.
JUDGE_RUNS = 8
SEED = 42
METRICS = ("context_precision", "faithfulness")


def main() -> int:
    cases = load_dataset(DATASET)
    print(f"loaded {len(cases)} cases", flush=True)
    target = OpenAICompatTarget(
        base_url=TARGET_URL, model=TARGET_MODEL, api_key=None,
        timeout_s=180.0, contexts_field="contexts",
    )

    # Phase 1: generate all responses once.
    responses = []
    for i, case in enumerate(cases, 1):
        responses.append((case, target.query(case.question)))
        print(f"[gen] {i}/{len(cases)} {case.id}", flush=True)

    # Phase 2: judge the same responses with each judge model.
    results = {}
    for jm in JUDGES:
        judge = OllamaJudge(model=jm, base_url=JUDGE_URL, cache=JudgeCache(), timeout_s=180.0)
        scores_by_metric = defaultdict(list)
        for ci, (case, resp) in enumerate(responses, 1):
            runs_by_metric = defaultdict(list)
            for run in range(JUDGE_RUNS):
                s = judge.score(case, resp, seed=SEED, run=run)
                for m, v in s.scores.items():
                    runs_by_metric[m].append(v)
            for m, vals in runs_by_metric.items():
                scores_by_metric[m].append(sum(vals) / len(vals))
            print(f"[judge {jm}] {ci}/{len(responses)}", flush=True)
        results[jm] = {}
        for m in METRICS:
            agg = aggregate_metric(m, scores_by_metric[m], confidence_level=0.95, seed=SEED)
            results[jm][m] = {"mean": agg.mean, "ci_low": agg.ci_low, "ci_high": agg.ci_high, "n": agg.n}
        cp = results[jm]["context_precision"]
        print(f"[RESULT] {jm}: context_precision={cp['mean']:.3f} "
              f"CI=[{cp['ci_low']:.3f},{cp['ci_high']:.3f}] n={cp['n']}", flush=True)

    print("FINAL " + json.dumps(results), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
