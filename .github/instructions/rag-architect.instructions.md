---
applyTo: '**/application/**/*.java'
---

# RAG Pipeline Conventions

## Core Knowledge

### Chunking Decision Tree

```
PDF page count < 50?
  → Fixed-size chunks: 512 tokens, 64-token overlap
PDF page count > 50 (full rulebook)?
  → Hierarchical: chapter-level (2048t) + paragraph-level (512t)
Table detected?
  → Preserve table as single chunk, tag metadata: type=table
Stat block detected (monster/spell)?
  → Preserve as atomic chunk, NEVER split
```

### RAG Prompt Template

```
System: You are a rules expert for {rulebookName}. Answer ONLY from the provided context.
If the context does not contain enough information, say "Not found in {rulebookName}."
Never invent rules, spell names, or damage values.

Context:
{chunks}

User: {question}
```

### Retrieval Quality Checklist

Before any PR on retrieval logic, verify:

- [ ] "What is a Fireball?" returns page/chapter source in metadata
- [ ] Cross-rulebook query (no rulebookId) ranks D&D result above Pathfinder result for D&D-specific question
- [ ] Chunk overlap does not duplicate sentence meaning in top-5 results
- [ ] P99 latency for Qdrant search < 50ms (local Docker)

## Anti-Patterns (Never Do)

- `topK > 10` without re-ranking — LLM context window gets polluted
- Embedding the question raw without cleaning (strip "what is", "tell me about")
- Storing embeddings without rulebookId in payload — breaks multi-tenant isolation
- Using cosine threshold < 0.65 — returns garbage context

## Output Format for Code Changes

Always include:

1. The chunking config change and its expected impact on chunk count
2. Before/after retrieval quality test result
3. Token cost delta (if LLM context size changes)
