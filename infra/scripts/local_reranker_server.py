#!/usr/bin/env python3
"""Native arm64 stand-in for the TEI /rerank endpoint.

HF's text-embeddings-inference publishes amd64-only images. On this Apple
Silicon Mac, running it under Colima's Rosetta emulation measured ~12s per
chunk (a 45-case x topN=30 eval sweep would take hours). This script serves
the same model (BAAI/bge-reranker-v2-m3) natively via sentence-transformers,
exposing the exact contract TeiRerankAdapter expects:

    POST /rerank {"query": str, "texts": [str, ...]}
    -> [{"index": int, "score": float}, ...]

No Java code changes needed: TeiRerankAdapter just talks to whatever is on
port 8090. Run instead of `docker compose up -d tei-reranker` when the
container is too slow to be practical; stop the container first (same port).

Uses Flask/Werkzeug (not stdlib http.server) so chunked transfer-encoding,
keep-alive, and IPv4/IPv6 dual-stack are handled correctly out of the box.
"""

from flask import Flask, jsonify, request
from sentence_transformers import CrossEncoder

MODEL_ID = "BAAI/bge-reranker-v2-m3"
PORT = 8090

print(f"Loading {MODEL_ID} (native arm64, first run downloads the model)...")
# device="cpu", not "mps": MPS measured a pathological slowdown for this model's
# shapes (30 realistic chunks: >90s and still not done, vs. 20.6s on plain CPU).
model = CrossEncoder(MODEL_ID, device="cpu")
print("Model ready.")

app = Flask(__name__)


@app.get("/health")
def health():
    return "", 200


@app.post("/rerank")
def rerank():
    payload = request.get_json()
    query, texts = payload["query"], payload["texts"]

    pairs = [[query, t] for t in texts]
    scores = model.predict(pairs)
    return jsonify([{"index": i, "score": float(s)} for i, s in enumerate(scores)])


if __name__ == "__main__":
    # threaded=True: the eval JVM keeps its HTTP connection alive across calls;
    # a single-threaded dev server would block subsequent requests on that idle socket.
    app.run(host="::", port=PORT, threaded=True)
