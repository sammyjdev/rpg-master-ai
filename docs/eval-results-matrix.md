# Eval results matrix (Slice 2)

At-a-glance results. Full method + caveats: `docs/eval-baseline.md`. All values are
on a **chunk-anchored synthetic set** → meaningful for **relative** before/after-rerank
comparison, not as absolute production numbers.

## Track A — retrieval baseline (N=45, threshold 0.3)

| k  | recall@k | MRR   |
|----|----------|-------|
| 3  | 1.000    | 0.948 |
| 5  | 1.000    | 0.948 |
| 8  | 1.000    | 0.948 |
| 10 | 1.000    | 0.948 |

Recall saturates at k=3; MRR is flat across the sweep.

## Track B — context_precision (two-judge, n=45, 8 runs, seed 42, identical inputs)

| judge model | context_precision | 95% CI        |
|-------------|-------------------|---------------|
| llama3.1:8b | 0.810             | 0.774 – 0.836 |
| gemma4:e4b  | 0.843             | 0.764 – 0.907 |

Two independent judges agree → robustness confirmed. (Snapshot, non-CI.)

## Reranking headroom (what Slice 3 must beat)

| metric            | current   | ceiling | headroom | rerank signal        |
|-------------------|-----------|---------|----------|----------------------|
| recall@k          | 1.000     | 1.0     | ~none    | saturated            |
| MRR               | 0.948     | 1.0     | ~0.05    | small                |
| context_precision | 0.81–0.84 | 1.0     | ~0.16–0.19 | **largest — primary** |

The precision half (context_precision) is the lever reranking should move most.
