---
applyTo: '**/*.java'
---

# Java 21 Conventions

## Core Patterns

### Virtual Thread Config (canonical — copy this exactly)

```java
// config/VirtualThreadConfig.java
@Configuration
public class VirtualThreadConfig {

    @Bean
    public AsyncTaskExecutor applicationTaskExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }

    // For Spring MVC — replaces Tomcat thread pool
    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadTomcatCustomizer() {
        return handler -> handler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
```

### Pattern Matching on Sealed IngestionResult (canonical)

```java
void handleResult(IngestionResult result) {
    switch (result) {
        case IngestionResult.Success s ->
            log.info("Stored {} chunks for doc {}", s.chunksStored(), s.documentId());
        case IngestionResult.Failed f ->
            log.error("Ingestion failed for {}: {}", f.documentId(), f.reason());
        case IngestionResult.Partial p ->
            log.warn("Partial ingestion: {}/{} chunks stored for {}",
                p.chunksStored(), p.chunksFailed(), p.documentId());
    }
}
```

### Virtual Thread Pinning — What to Watch

```
PINNING CAUSES (avoid these inside Virtual Threads):
  - synchronized blocks/methods → replace with ReentrantLock
  - native method calls that block → isolate in platform thread pool
  - PDFBox: some PDF parsing operations are synchronized internally
    → wrap PDFBox calls in a dedicated platform thread pool if pinning detected

DETECTION:
  -Djdk.tracePinnedThreads=full  (add to JVM args during load test)
```

### SequencedCollection for Chunk Windows

```java
// Correct use of Java 21 SequencedCollection
List<Chunk> chunks = new ArrayList<>(parsedChunks); // SequencedCollection
Chunk first = chunks.getFirst();   // replaces get(0)
Chunk last  = chunks.getLast();    // replaces get(size-1)
List<Chunk> reversed = chunks.reversed(); // no-copy reverse view
```

## Code Review Checklist

- [ ] No `@Data`, `@Builder`, `@Getter` in `shared-domain`
- [ ] All domain types are Records or Sealed interfaces
- [ ] No `synchronized` keyword in Virtual Thread hot paths
- [ ] `switch` on sealed types is exhaustive (no `default` branch)
- [ ] No raw `Thread.sleep()` — use `Awaitility` in tests

## Anti-Patterns

- Fixed thread pools for I/O tasks — use Virtual Threads
- Mutable fields in Records — Records are immutable by design
- `instanceof` chains — use pattern matching switch
