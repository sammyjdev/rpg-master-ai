---
agent: ask
description: Review Java file for Java 21 idiom correctness
---

Review the selected Java code (or the file `${file}`) for Java 21 idiom correctness.

For each finding, indicate:

- **Severity:** `BLOCKER` (wrong idiom that must change) or `SUGGESTION` (improvement)
- **Line reference** and current code
- **What it should be** with corrected code snippet
- **Why** it matters for this codebase

Check specifically for:

**Records & Domain Types**

- Are all domain types in `shared-domain` Records (not classes with Lombok)?
- Do Records have any mutable state (arrays, collections not wrapped)?
- Are there any `@Data`, `@Builder`, or `@Getter` annotations?

**Sealed Classes & Pattern Matching**

- Are all `switch` statements on sealed types exhaustive (no `default` branch)?
- Are there `instanceof` chains that should be `switch` pattern matching?
- Are there unchecked casts that should be caught by pattern matching?

**Virtual Threads**

- Are there `synchronized` blocks/methods in Virtual Thread hot paths? (Use `ReentrantLock` instead)
- Are there fixed-size `ExecutorService` for I/O tasks? (Use `newVirtualThreadPerTaskExecutor()`)
- Any `CompletableFuture.allOf()` that should be `StructuredTaskScope`?

**SequencedCollection**

- Any `list.get(0)` → should be `list.getFirst()`
- Any `list.get(list.size() - 1)` → should be `list.getLast()`

**Text Blocks**

- Any multi-line SQL or LLM prompts built with String concatenation → should use `"""`

**General**

- Constructor injection only — no `@Autowired` on fields
- SLF4J logging — no `System.out.println`
- `ResponseEntity<>` on all controller methods
