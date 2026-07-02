# Slice 2 — Synthetic golden-set + GNOMON precision snapshot — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grow the retrieval golden set from N=3 to N≈43 by synthesizing cases from the ingested corpus with a one-shot offline LLM, record the new recall@k/MRR baseline, then snapshot `context_precision` via GNOMON with a GPU-desktop judge.

**Architecture:** Track A — a Python tooling script samples chunks already in Qdrant, filters to answerable prose, asks OpenRouter to write one Q&A per chunk, and appends the results (chunk-anchored, so `relevantPages` = the source chunk's page) to the committed `golden-qa.json`; the existing `./gradlew :app:eval` then records the new baseline with zero LLM. Track B — point GNOMON's judge at the desktop Ollama over Tailscale, regenerate its dataset from the expanded golden set, and run it twice (two judges) for a `context_precision` robustness snapshot.

**Tech Stack:** Java 21 / Spring Boot (eval harness, unchanged), Python 3 + `requests` (generator script, `infra/scripts/`), Qdrant HTTP scroll API, OpenRouter (OpenAI-compat, offline one-shot), Ollama on the GPU desktop (GNOMON judge), GNOMON (`~/dev/gnomon-eval`).

## Global Constraints

- **Python only under `infra/scripts/`** — no Python elsewhere (repo rule).
- **Secrets never committed** — `OPENROUTER_API_KEY` from env only; `.gitignore` any local key file.
- **Golden set schema (verified):** JSON array of `{id, question, expectedAnswer, relevantPages:[{rulebookId, pageNumber}]}`. `GoldenSet.load` rejects: blank id/question/expectedAnswer, empty `relevantPages`, `pageNumber < 1`, and **multi-rulebook cases** (exactly one rulebook per case).
- **Keep the 3 hand-verified seeds** `gq-001`/`gq-002` (EN) + `gq-003` (PT). Generated cases are **Portuguese**.
- **Qdrant:** collection `rpg-chunks`, payload keys `text`, `document_id`, `rulebook_id`, `page_number` (int). HTTP :6343, gRPC :6334 (app uses gRPC). rulebookIds: `dnd-5e-phb`, `dnd-5e-mm`, `dnd-5e-dmg`.
- **Compute split:** embeddings + generation on the Mac (match the Mac-ingested index); only the GNOMON **judge** runs on the desktop `http://<judge-host>:11434` (RTX 4070 Ti, 12 GB). Access: memory `desktop-model-engine`.
- **Sandbox:** any docker / gradle / network / ollama command needs `dangerouslyDisableSandbox: true`.
- **Git hygiene:** `git add` ONLY the exact paths named per task — the repo has unrelated uncommitted changes (`CLAUDE.md`, `.axon/`, `docs/agents/`, `rpgm`) that must NEVER be staged. Commit with `--no-gpg-sign` (gpg-agent blocked in sandbox); the range is re-signed at the end outside the sandbox.

---

## File structure

- `infra/scripts/generate_golden_set.py` (new) — the one-shot generator. Sole responsibility: sample+filter Qdrant chunks → OpenRouter Q&A → assemble+validate → write `golden-qa.json`. Has a `--selftest` mode for its pure filter logic (no network).
- `infra/scripts/requirements.txt` (new) — single dep `requests`.
- `app/src/test/resources/golden-qa.json` (modify) — seeds kept, generated cases appended.
- `eval/gnomon/config.toml` (modify) — `[judge] base_url` → desktop; `model` swapped per run.
- `eval/gnomon/dataset.json` (regenerated artifact) — via existing `GnomonDatasetExport`.
- `docs/eval-baseline.md` (modify) — new Track A baseline (N≈43) + Track B snapshot section (replaces the stale "not yet run / needs OpenRouter" text).
- `eval/reports/retrieval-<millis>.md` (new artifact) — from `./gradlew :app:eval`.

No Java source changes: `GnomonDatasetExport` and `RetrievalBaselineEval` re-read `golden-qa.json` as-is.

---

## Track A — synthetic golden set

### Task 1: Generator script (pure filter logic + self-test)

**Files:**
- Create: `infra/scripts/generate_golden_set.py`
- Create: `infra/scripts/requirements.txt`

**Interfaces:**
- Produces: `is_answerable(text: str) -> bool` (pure), `sample_chunks(rulebook, n, seen_pages) -> list[dict]`, `synthesize(excerpt: str) -> dict` returning `{"question": str, "expectedAnswer": str}`, and a `main()` that writes `app/src/test/resources/golden-qa.json`.
- Consumes (runtime, Task 2): env `OPENROUTER_API_KEY`, optional `OPENROUTER_MODEL` (default below), optional `QDRANT_HTTP` (default `http://localhost:6343`).

- [ ] **Step 1: Write `requirements.txt`**

File `infra/scripts/requirements.txt`:
```
requests>=2.31
```

- [ ] **Step 2: Write the script**

File `infra/scripts/generate_golden_set.py`:
```python
#!/usr/bin/env python3
"""One-shot generator for the retrieval golden set (Slice 2, Track A).

Samples answerable prose chunks already in Qdrant, asks an OpenRouter model to
write one PT Q&A per chunk, and writes app/src/test/resources/golden-qa.json
(keeping the 3 hand-verified seeds). Chunk-anchored: relevantPages = the source
chunk's page, so recall is inflated in absolute terms but constant across a
before/after-rerank comparison. Re-runnable; output is committed.

Usage:
  OPENROUTER_API_KEY=sk-... python3 generate_golden_set.py            # generate
  python3 generate_golden_set.py --selftest                           # no network
Env:
  OPENROUTER_API_KEY (required for generate), OPENROUTER_MODEL
  (default openai/gpt-4o-mini), QDRANT_HTTP (default http://localhost:6343),
  PER_RULEBOOK (default 14).
"""
import json
import os
import re
import sys
from pathlib import Path

import requests

RULEBOOKS = ["dnd-5e-phb", "dnd-5e-mm", "dnd-5e-dmg"]
COLLECTION = "rpg-chunks"
OUT = Path(__file__).resolve().parents[2] / "app/src/test/resources/golden-qa.json"
SEEDS = [
    {"id": "gq-001", "question": "What is an Ankheg's armor class?",
     "expectedAnswer": "AC 14 (natural armor).",
     "relevantPages": [{"rulebookId": "dnd-5e-mm", "pageNumber": 20}]},
    {"id": "gq-002", "question": "How much damage does a Fireball spell deal?",
     "expectedAnswer": "8d6 fire damage on a failed Dexterity save, half on success.",
     "relevantPages": [{"rulebookId": "dnd-5e-phb", "pageNumber": 222}]},
    {"id": "gq-003", "question": "Quanto de dano causa uma bola de fogo?",
     "expectedAnswer": "8d6 de dano de fogo; metade com sucesso no teste de Destreza.",
     "relevantPages": [{"rulebookId": "dnd-5e-phb", "pageNumber": 222}]},
]

MIN_CHARS = 250
MIN_ALPHA_RATIO = 0.65


def is_answerable(text: str) -> bool:
    """Keep dense prose; drop short or garbled stat-block junk."""
    if not text or len(text) < MIN_CHARS:
        return False
    letters = sum(c.isalpha() or c.isspace() for c in text)
    return (letters / len(text)) >= MIN_ALPHA_RATIO


def _scroll(qdrant_http: str, rulebook: str):
    """Yield payloads for one rulebook via Qdrant HTTP scroll."""
    offset = None
    while True:
        body = {
            "limit": 256,
            "with_payload": True,
            "with_vector": False,
            "filter": {"must": [{"key": "rulebook_id", "match": {"value": rulebook}}]},
        }
        if offset is not None:
            body["offset"] = offset
        r = requests.post(f"{qdrant_http}/collections/{COLLECTION}/points/scroll",
                          json=body, timeout=30)
        r.raise_for_status()
        result = r.json()["result"]
        for p in result["points"]:
            yield p["payload"]
        offset = result.get("next_page_offset")
        if offset is None:
            return


def sample_chunks(qdrant_http: str, rulebook: str, n: int) -> list[dict]:
    """Answerable chunks for one rulebook, stratified across page ranges."""
    payloads = [p for p in _scroll(qdrant_http, rulebook) if is_answerable(p.get("text", ""))]
    payloads.sort(key=lambda p: p.get("page_number", 0))
    if len(payloads) <= n:
        return payloads
    # even stride across the page-sorted list => spread over the whole book
    stride = len(payloads) / n
    return [payloads[int(i * stride)] for i in range(n)]


def synthesize(model: str, api_key: str, excerpt: str) -> dict:
    """One PT question + concise PT answer from a rulebook excerpt."""
    prompt = (
        "Este e um trecho de um livro de regras de D&D 5e (em portugues):\n\n"
        f"\"\"\"\n{excerpt}\n\"\"\"\n\n"
        "Gere UMA pergunta natural de jogador ou mestre que seja respondivel "
        "SOMENTE com este trecho, e uma resposta concisa. Responda em portugues. "
        'Retorne APENAS JSON: {"question": "...", "expectedAnswer": "..."}'
    )
    r = requests.post(
        "https://openrouter.ai/api/v1/chat/completions",
        headers={"Authorization": f"Bearer {api_key}"},
        json={"model": model, "temperature": 0.3,
              "messages": [{"role": "user", "content": prompt}],
              "response_format": {"type": "json_object"}},
        timeout=90,
    )
    r.raise_for_status()
    content = r.json()["choices"][0]["message"]["content"]
    obj = json.loads(content)
    q, a = obj.get("question", "").strip(), obj.get("expectedAnswer", "").strip()
    if not q or not a:
        raise ValueError("blank question/answer")
    if q.lower() in excerpt.lower():
        raise ValueError("question is a verbatim slice of the excerpt")
    return {"question": q, "expectedAnswer": a}


def main() -> int:
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        print("OPENROUTER_API_KEY not set", file=sys.stderr)
        return 1
    model = os.environ.get("OPENROUTER_MODEL", "openai/gpt-4o-mini")
    qdrant_http = os.environ.get("QDRANT_HTTP", "http://localhost:6343")
    per_book = int(os.environ.get("PER_RULEBOOK", "14"))

    cases = list(SEEDS)
    seq = len(SEEDS)
    for rb in RULEBOOKS:
        chunks = sample_chunks(qdrant_http, rb, per_book)
        print(f"{rb}: {len(chunks)} answerable chunks sampled", file=sys.stderr)
        made = 0
        for p in chunks:
            try:
                qa = synthesize(model, api_key, p["text"])
            except Exception as e:  # skip a bad chunk/response, keep going
                print(f"  skip page {p.get('page_number')}: {e}", file=sys.stderr)
                continue
            seq += 1
            cases.append({
                "id": f"gq-{seq:03d}",
                "question": qa["question"],
                "expectedAnswer": qa["expectedAnswer"],
                "relevantPages": [{"rulebookId": rb, "pageNumber": int(p["page_number"])}],
            })
            made += 1
        print(f"{rb}: {made} cases generated", file=sys.stderr)

    OUT.write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
    print(f"Wrote {len(cases)} cases to {OUT}")
    return 0


def _selftest() -> int:
    assert is_answerable("a" * 300) is True
    assert is_answerable("short") is False
    assert is_answerable("123 45 +6 /7 |8 " * 40) is False  # low alpha ratio
    assert is_answerable("Uma criatura pode usar sua acao para " * 10) is True
    print("selftest OK")
    return 0


if __name__ == "__main__":
    sys.exit(_selftest() if "--selftest" in sys.argv else main())
```

- [ ] **Step 3: Run the self-test (no network)**

Run: `python3 infra/scripts/generate_golden_set.py --selftest`
Expected: prints `selftest OK`, exit 0.

- [ ] **Step 4: Commit**

```bash
git add infra/scripts/generate_golden_set.py infra/scripts/requirements.txt
git commit --no-gpg-sign -m "feat: golden-set generator script (Track A, chunk-anchored)"
```

---

### Task 2: Generate the expanded golden set

**Files:**
- Modify: `app/src/test/resources/golden-qa.json` (overwritten by the script: seeds + ~40 generated)

**Interfaces:**
- Consumes: Task 1's script; a running Qdrant (corpus ingested) and `OPENROUTER_API_KEY`.
- Produces: `golden-qa.json` with N≈43 that passes `GoldenSet.load`.

- [ ] **Step 1: Ensure Qdrant is up (corpus ingested)**

Run (dangerouslyDisableSandbox): `curl -s http://localhost:6343/collections/rpg-chunks | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['points_count'])"`
Expected: `2443` (or close). If it errors, start Qdrant:
`docker run -d --name rpg-qdrant -p 6334:6334 -p 6343:6333 -e QDRANT__SERVICE__GRPC_PORT=6334 -v rpg_qdrant_data:/qdrant/storage qdrant/qdrant:v1.12.5` then re-check.

- [ ] **Step 2: Run the generator (network + LLM)**

Run (dangerouslyDisableSandbox, env `OPENROUTER_API_KEY` already set):
`python3 infra/scripts/generate_golden_set.py`
Expected: stderr shows per-rulebook sampled/generated counts; stdout `Wrote 4x cases to .../golden-qa.json`. Target ~43; if far short (many skips), lower `MIN_CHARS`/`MIN_ALPHA_RATIO` or raise `PER_RULEBOOK` and re-run.

- [ ] **Step 3: Validate the generated set via the loader**

Run (dangerouslyDisableSandbox): `./gradlew :app:test --tests 'com.rpgmaster.app.eval.GoldenSetTest'`
Expected: PASS (proves the new JSON parses, is single-rulebook per case, no blanks, valid pages).

- [ ] **Step 4: Eyeball a few cases**

Run: `python3 -c "import json;d=json.load(open('app/src/test/resources/golden-qa.json'));print(len(d));[print(c['id'],c['question'][:70]) for c in d[:6]]"`
Expected: count ≈43; seeds `gq-001..003` present first; generated questions read as natural PT.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/resources/golden-qa.json
git commit --no-gpg-sign -m "test: expand golden set to N~43 (synthetic, chunk-anchored)"
```

---

### Task 3: Record the new Track A baseline

**Files:**
- Create: `eval/reports/retrieval-<millis>.md` (harness output)
- Modify: `docs/eval-baseline.md`

**Interfaces:**
- Consumes: expanded `golden-qa.json`; live Qdrant + Mac Ollama (bge-m3).
- Produces: a recorded N≈43 recall@k/MRR sweep.

- [ ] **Step 1: Ensure Mac Ollama is up (bge-m3)**

Run (dangerouslyDisableSandbox): `curl -s http://localhost:11434/api/tags | grep -c bge-m3`
Expected: `>=1`. If Ollama is down: `ollama serve` (background), `ollama pull bge-m3` if missing.

- [ ] **Step 2: Run the eval harness**

Run (dangerouslyDisableSandbox): `./gradlew :app:eval`
Expected: PASS; console prints the sweep table and `Wrote .../eval/reports/retrieval-<millis>.md`. Note the new file name.

- [ ] **Step 3: Update `docs/eval-baseline.md`**

In `docs/eval-baseline.md`, under "## Retrieval (deterministic, `./gradlew :app:eval`)": replace the "Golden set: 3 cases…" line and the sweep table with the new N≈43 numbers from Step 2's report. Add one sentence: "Cases are chunk-derived (synthetic), so recall is inflated in absolute terms; the number is for relative before/after-rerank comparison, where the inflation cancels — not absolute production recall." Update the "Recommended `k`" paragraph to state what the larger N now supports about `topK`. Remove the now-obsolete "N=3, not statistical" caveat bullet (keep the others).

- [ ] **Step 4: Commit**

```bash
git add docs/eval-baseline.md eval/reports/retrieval-*.md
git commit --no-gpg-sign -m "test: record Track A baseline at N~43 + note synthetic inflation"
```

---

## Track B — `context_precision` snapshot (desktop judge, two-judge robustness)

### Task 4: Point GNOMON judge at the desktop + regenerate dataset

**Files:**
- Modify: `eval/gnomon/config.toml`
- Regenerate: `eval/gnomon/dataset.json`

**Interfaces:**
- Consumes: expanded `golden-qa.json`; desktop Ollama reachable at `http://<judge-host>:11434`.
- Produces: `dataset.json` with N≈43; `config.toml` judging on the desktop.

- [ ] **Step 1: Confirm the desktop judge models are present**

Run (dangerouslyDisableSandbox): `curl -s http://<judge-host>:11434/api/tags | python3 -c "import sys,json;n=[m['name'] for m in json.load(sys.stdin)['models']];print('llama3.1:8b' in n, 'gemma4:e4b' in n)"`
Expected: `True True`. If `llama3.1:8b` is missing: `curl -s http://<judge-host>:11434/api/pull -d '{"model":"llama3.1:8b"}' | tail -c 80`.

- [ ] **Step 2: Set the judge base_url in `config.toml`**

In `eval/gnomon/config.toml`, edit the `[judge]` block so it reads (leave `provider = "ollama"`; set `base_url` to the desktop; `model` is overridden per run in Task 5 so the committed default is `llama3.1:8b`):
```toml
[judge]
provider = "ollama"
model = "llama3.1:8b"
base_url = "http://<judge-host>:11434"
```

- [ ] **Step 3: Regenerate the GNOMON dataset from the expanded golden set**

Run (dangerouslyDisableSandbox): `./gradlew :app:integrationTest --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'`
Expected: PASS; prints `Wrote .../eval/gnomon/dataset.json`.

- [ ] **Step 4: Verify the dataset count**

Run: `python3 -c "import json;print(len(json.load(open('eval/gnomon/dataset.json'))))"`
Expected: ≈43 (matches golden set).

- [ ] **Step 5: Commit**

```bash
git add eval/gnomon/config.toml eval/gnomon/dataset.json
git commit --no-gpg-sign -m "test: GNOMON judge on desktop GPU + dataset regen for N~43"
```

---

### Task 5: Two-judge `context_precision` snapshot

**Files:**
- Modify: `docs/eval-baseline.md`

**Interfaces:**
- Consumes: `dataset.json`, `config.toml`, the running app on the Mac, GNOMON installed at `~/dev/gnomon-eval`.
- Produces: two recorded `context_precision` + 95% CI values.

- [ ] **Step 1: Ensure GNOMON is installed**

Run (dangerouslyDisableSandbox): `cd ~/dev/gnomon-eval && python3 -c "import gnomon" 2>/dev/null && echo OK || pip install -e ".[dev]"`
Expected: `OK` (or install completes).

- [ ] **Step 2: Start the app on the Mac (generator = local qwen2.5:7b)**

Run (dangerouslyDisableSandbox, background): `SPRING_PROFILES_ACTIVE=local,api ./gradlew :app:bootRun`
Wait for port 8080 (GNOMON target `base_url = http://localhost:8080/v1`) and 8082 healthy. Confirm: `curl -s http://localhost:8080/v1/models` returns JSON.

- [ ] **Step 3: Run GNOMON with judge llama3.1:8b**

Run (dangerouslyDisableSandbox): `cd eval/gnomon && gnomon --config config.toml`
Expected: reports `faithfulness` + `context_precision` each with a 95% CI. Record `context_precision` (llama3.1:8b).

- [ ] **Step 4: Run GNOMON with judge gemma4:e4b**

Override the judge model for one run without editing the committed default. Run (dangerouslyDisableSandbox):
`cd eval/gnomon && cp config.toml /tmp/gnomon-gemma.toml && sed -i '' 's/model = "llama3.1:8b"/model = "gemma4:e4b"/' /tmp/gnomon-gemma.toml && gnomon --config /tmp/gnomon-gemma.toml`
Expected: a second `context_precision` + CI. Record it (gemma4:e4b).

- [ ] **Step 5: Record both in `docs/eval-baseline.md`**

Replace the stale "## Answer quality (GNOMON, Track B) — not yet run" section (the one that says the LLM must be pointed at OpenRouter) with:
```markdown
## Answer quality (GNOMON, Track B) — snapshot, non-CI

Recorded <DATE>. Generator = qwen2.5:7b (Mac Ollama). Judge = desktop Ollama
(RTX 4070 Ti) over Tailscale. Two judges for robustness; `faithfulness` omitted
(generation-side, out of scope). This is an exploratory snapshot for the
before/after-rerank comparison, not a reproducible committed baseline and not a
CI gate — it depends on the desktop being up.

| judge model  | context_precision | 95% CI        |
|--------------|-------------------|---------------|
| llama3.1:8b  | <val>             | <lo>–<hi>     |
| gemma4:e4b   | <val>             | <lo>–<hi>     |

Agreement across the two independent judges is the robustness signal; large
divergence would flag judge-sensitivity to resolve before trusting the metric.
```
Fill `<DATE>` and the four numbers from Steps 3-4.

- [ ] **Step 6: Stop the app and commit**

Stop the `bootRun` background task. Then:
```bash
git add docs/eval-baseline.md
git commit --no-gpg-sign -m "test: GNOMON context_precision snapshot (two-judge, Track B)"
```

---

## Finalization (after all tasks)

- [ ] **Re-sign the commit range outside the sandbox** (user runs via `!` for the pinentry passphrase; last signed tip is `4cf7452`):
```bash
git rebase --autostash 4cf7452 --exec "git commit --amend --no-edit -S"
```
- [ ] **Confirm** no unrelated paths were staged: `git show --stat` on each new commit touches only the paths named in its task.

---

## Self-review

**Spec coverage:**
- Track A generator (`infra/scripts/generate_golden_set.py`, sample+filter+LLM+per-case assert) → Task 1 + Task 2. ✓
- Keep 3 seeds, PT, ~43, single-rulebook → Task 1 (`SEEDS`, per-book loop), validated Task 2 Step 3. ✓
- Validity note (chunk-anchored inflation) in `eval-baseline.md` → Task 3 Step 3. ✓
- Track A new baseline via `./gradlew :app:eval` into `eval/reports/` + doc → Task 3. ✓
- Track B: judge on desktop, generator+embeddings on Mac, two-judge robustness, context_precision only → Tasks 4-5. ✓
- GNOMON judge base_url change only (no external-repo change) → Task 4 Step 2. ✓
- Snapshot labelled non-CI, generator/judges/date recorded → Task 5 Step 5. ✓
- Secrets from env, never committed → Global Constraints + Task 1 (no key in script). ✓

**Placeholder scan:** the only `<...>` are the runtime-measured numbers/date in Task 5's doc template (unknowable until the run) — acceptable, they are explicitly "fill from Steps 3-4". No TODO/TBD in code steps.

**Type consistency:** `is_answerable`, `sample_chunks`, `synthesize`, `main`, `_selftest` used consistently across Task 1 and referenced in Task 2. Qdrant payload keys (`text`, `page_number`, `rulebook_id`) match `QdrantVectorStoreAdapter`. Golden schema matches `GoldenCase`/`RelevantPage`. GNOMON dataset field names come from the existing `GnomonDatasetExport` (unchanged). ✓
