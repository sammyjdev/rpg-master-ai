---
agent: ask
description: Draft a LinkedIn article for a completed RPG Master AI milestone
---

Draft a LinkedIn article for the following completed milestone:

${input:Milestone description (e.g., "Increment 1 CLI — synchronous RAG pipeline with Qdrant + Ollama")}

Follow this exact structure:

## Structure

**HOOK (2 lines max)**
An RPG scenario that makes the reader feel the technical problem. No "I'm excited to share", no "journey". Drop them into the action.

**THE PROBLEM (3–4 lines)**
What fails without this solution. Include a real failure mode: a latency number, wrong answer, or data loss scenario.

**WHY NOT THE OBVIOUS SOLUTION (2–3 lines)**
Name the alternative you considered and why it loses. Shows technical judgment, not just implementation.

**THE SOLUTION (code block)**
15–30 lines. Clean. Include a comment with the file path at the top. No boilerplate, no imports. Just the signal.

**THE LESSON (3–4 lines)**
What surprised you. What Java 21 / Spring AI / Qdrant taught you that you did NOT expect going in.

**CTA**

- `"Full implementation: [specific commit link]"`
- One open question for comments

## RPG Analogy Reference

| Technical Concept        | RPG Analogy                                                                                      |
| ------------------------ | ------------------------------------------------------------------------------------------------ |
| RAG pipeline             | Dungeon Master consulting a rulebook before answering a player question                          |
| Vector similarity search | "Find the spell that sounds most like what the player described"                                 |
| Chunking strategy        | Dividing a rulebook into indexed tabs — too big = can't find anything, too small = loses context |
| Multi-rulebook namespace | Campaign setting isolation: Forgotten Realms rules shouldn't answer Eberron questions            |
| Kafka DLQ                | The resurrect spell — failed events go to limbo, not to the void                                 |
| Virtual Threads          | Having 10,000 runners that sleep when waiting (vs 200 that block)                                |
| Structured Concurrency   | Two party members doing tasks simultaneously — if one dies, both stop and regroup                |
| Qdrant ANN search        | Fuzzy search in the monster manual — close enough is good enough                                 |
| Hexagonal Architecture   | The rules engine that works the same whether the DM reads from a book, PDF, or website           |

## Publishing Checklist

- [ ] Commit linked in article is green in CI
- [ ] Code snippet compiles and runs as shown
- [ ] RPG analogy explained in first paragraph (non-RPG readers must understand it)
- [ ] At least one concrete number (latency, token count, chunk count)
- [ ] No clichés: no "dive into", "journey", "leverage", "ecosystem", "excited to share"
- [ ] GitHub repo link in first comment (not in article body — LinkedIn penalizes external links)
- [ ] Post Tuesday or Thursday 8–10am BRT (highest B2B engagement window)

## Anti-Patterns

- "In this article, I will..." — start with the action, not the meta-commentary
- Article about what you plan to build — only ship articles about what you built
- No code in the article — engineers scroll past walls of text
- Posting all articles in the same week — space them to match the sprint schedule
