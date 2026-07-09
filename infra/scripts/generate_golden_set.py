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
