---
applyTo: '**/adapter/outbound/SpringAi*.java'
---

# Spring AI Conventions

## Core Patterns

### Spring AI Ollama Config (canonical — application-local.yml)

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      embedding:
        model: nomic-embed-text # 768 dimensions, fast local embedding
      chat:
        model: llama3.2:3b # fast enough for dev, 3B params
```

### EmbeddingPort Adapter (canonical)

```java
// adapter/outbound/SpringAiEmbeddingAdapter.java
@Component
@Profile("!prod")
public class SpringAiEmbeddingAdapter implements EmbeddingPort {

    private final EmbeddingModel embeddingModel;

    public SpringAiEmbeddingAdapter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public List<Float> embed(String text) {
        return embeddingModel.embed(text).stream()
            .map(Double::floatValue)
            .toList();
    }

    @Override
    public List<List<Float>> embedBatch(List<String> texts) {
        // Use batch API — 10x faster than calling embed() in a loop
        return embeddingModel.embed(texts).stream()
            .map(arr -> Arrays.stream(arr).mapToObj(Double::floatValue).toList())
            .toList();
    }
}
```

### LlmPort Adapter with Token Tracking

```java
@Component
public class SpringAiLlmAdapter implements LlmPort {

    private final ChatModel chatModel;
    private final MeterRegistry meterRegistry;

    @Override
    public Flux<String> generateStream(String systemPrompt, String userPrompt, List<String> context) {
        var prompt = new Prompt(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(buildAugmentedPrompt(userPrompt, context))
        ));

        return chatModel.stream(prompt)
            .map(ChatResponse::getResult)
            .map(Generation::getOutput)
            .map(AssistantMessage::getContent)
            .doOnComplete(() ->
                meterRegistry.counter("rpg.query.tokens_used",
                    "direction", "output").increment()
            );
    }
}
```

## Prompt Template Registry

All prompts live in `src/main/resources/prompts/`. Never hardcode prompts in Java.

```
resources/prompts/
├── rag-system.st          ← Main RAG system prompt (StringTemplate)
├── rag-user.st            ← User prompt with context injection
└── query-rewrite.st       ← Optional: query expansion prompt
```

## Anti-Patterns

- Hardcoded model names in Java code — always use `application.yml`
- Calling `embed()` in a loop — use `embedBatch()` for chunk processing
- No token tracking — cost blindness is a production incident waiting to happen
