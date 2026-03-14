---
agent: ask
description: Design or review chunking strategy for a new PDF document type
---

Help design or review the chunking strategy for this document/use case.

Context: RPG Master AI uses Apache PDFBox to parse rulebook PDFs. The embedding model is `nomic-embed-text` (768 dimensions, ~512 token context window). Chunks are stored in Qdrant with `rulebook_id` payload filter for multi-tenant isolation.

**Current defaults:**

- Fixed-size: 512 tokens per chunk
- Overlap: 64 tokens
- Chunk type tags: `paragraph`, `table`, `stat_block`

---

Answer these questions based on the document type or issue described:

1. **Chunk size recommendation** — What is the right token window (256/512/1024/2048)? Why?
2. **Overlap recommendation** — How much overlap prevents context fragmentation without duplicating meaning in top-5 results?
3. **Special content handling** — Does this document have tables, stat blocks, spell descriptions, or other structured content that must be preserved as atomic chunks? How to detect them in PDFBox?
4. **Hierarchical chunking need** — Should we index at multiple granularities (chapter-level + paragraph-level) for this document? What are the query patterns that justify it?
5. **Expected chunk count** — Roughly how many chunks for this document at the recommended size?
6. **Token cost impact** — If we send top-5 chunks to the LLM, what is the approximate context size per query?
7. **Retrieval quality test** — What specific test question should return a specific chunk to verify the strategy works? (e.g., "What is the range of Fireball?" should return the Evocation chapter chunk)

Follow the decision tree from AGENT-RAG-ARCHITECT.md:

- < 50 pages → fixed-size 512/64
- > 50 pages → hierarchical 2048 (chapter) + 512 (paragraph)
- Tables → preserve as single chunk with `type=table`
- Stat blocks → atomic chunk, never split
