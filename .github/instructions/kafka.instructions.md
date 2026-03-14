---
applyTo: '**/*Kafka*.java'
---

# Kafka & Event-Driven Conventions

## Topic Topology

```
[api-gateway] ──publish──► [document.ingested]
                                    │
                            [document-processor]
                                    │
                           ◄────────┴────────►
                    (success)              (failure)
                [document.chunked]    [document.dlq]
                        │
               [embedding-service]
                        │
               [document.embedded]
                        │
              [rulebook-registry]
```

## Event Schemas

All events are Records in `shared-domain/src/main/java/.../events/`. Never use `String` or `Map` as message values.

```java
record DocumentIngestedEvent(
    String eventId,         // UUID — idempotency key
    String documentId,      // FK to documents table
    String rulebookId,
    String s3Key,
    String filename,
    Instant occurredAt
) {}

record DocumentChunkedEvent(
    String eventId,
    String documentId,
    String rulebookId,
    List<String> chunkIds,
    int totalChunks,
    Instant occurredAt
) {}

record DeadLetterEvent(
    String originalEventId,
    String originalTopic,
    String documentId,
    String errorType,       // PARSING_FAILED | EMBEDDING_FAILED | STORAGE_FAILED
    String errorMessage,
    int attemptCount,
    Instant failedAt
) {}
```

## Topic Config (canonical)

```java
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic documentIngestedTopic() {
        return TopicBuilder.name("document.ingested")
            .partitions(3)
            .replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(7).toMillis()))
            .build();
    }

    @Bean
    public NewTopic documentDlqTopic() {
        return TopicBuilder.name("document.dlq")
            .partitions(1)
            .replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(Duration.ofDays(30).toMillis()))
            .build();
    }
}
```

## Consumer Config (canonical)

```java
@KafkaListener(
    topics = "document.ingested",
    groupId = "document-processor",
    containerFactory = "kafkaListenerContainerFactory"
)
@RetryableTopic(
    attempts = "3",
    backoff = @Backoff(delay = 1000, multiplier = 2.0),
    dltTopicSuffix = ".dlq",
    dltStrategy = DltStrategy.FAIL_ON_ERROR
)
public void consume(DocumentIngestedEvent event, @Header KafkaHeaders.RECEIVED_KEY String key) {
    // Idempotency check FIRST — at-least-once delivery will redeliver
    if (ingestionJobRepository.existsByEventId(event.eventId())) {
        log.info("Duplicate event {}, skipping", event.eventId());
        return;
    }
    documentIngestionUseCase.process(event);
}
```

## Producer Config (canonical)

```java
@Configuration
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        var config = properties.buildProducerProperties();
        config.put(ProducerConfig.ACKS_CONFIG, "all");              // strongest durability
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // exactly-once producer
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(config);
    }
}
```

## Anti-Patterns

- Auto-creating topics via `spring.kafka.admin.auto-create=true` in prod — use explicit `NewTopic` beans
- No idempotency check — at-least-once delivery will redeliver the same event
- `String` message values — always use typed event Records with Jackson serialization
- Consuming and processing without error boundary — wrap in try-catch, publish uncaught exceptions to DLQ
- Consumer group ID not unique per service — each service must have its own `groupId`
