---
applyTo: '**/*Qdrant*.java'
---

# Qdrant Vector Store Conventions

## Collection Schema

```json
{
  "collection_name": "rpg-chunks",
  "vector_size": 768,
  "distance": "Cosine",
  "payload_schema": {
    "chunk_id": "keyword",
    "document_id": "keyword",
    "rulebook_id": "keyword",
    "text": "text",
    "page_number": "integer",
    "chunk_index": "integer",
    "chunk_type": "keyword",
    "token_count": "integer"
  }
}
```

### Index Config (canonical — create programmatically at startup)

```java
@Component
public class QdrantCollectionInitializer implements ApplicationRunner {

    private final QdrantClient client;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        var collections = client.listCollectionsAsync().get();
        if (collections.stream().noneMatch(c -> c.getName().equals("rpg-chunks"))) {
            client.createCollectionAsync("rpg-chunks",
                VectorsConfig.newBuilder()
                    .setParams(VectorParams.newBuilder()
                        .setSize(768)
                        .setDistance(Distance.Cosine)
                        .build())
                    .build()
            ).get();

            // Index rulebook_id for fast filtering — do this at collection creation
            client.createPayloadIndexAsync(
                "rpg-chunks", "rulebook_id",
                PayloadSchemaType.Keyword,
                null, null
            ).get();
        }
    }
}
```

## Search Patterns

### Standard RAG Search (canonical)

```java
public List<ScoredChunk> search(String rulebookId, List<Float> queryVector,
                                 int topK, float threshold) {
    var filter = Filter.newBuilder()
        .setMust(Condition.newBuilder()
            .setFieldCondition(FieldCondition.newBuilder()
                .setKey("rulebook_id")
                .setMatch(Match.newBuilder()
                    .setValue(MatchValue.newBuilder()
                        .setStringValue(rulebookId)
                        .build())
                    .build())
                .build())
            .build())
        .build();

    return client.searchAsync(SearchPoints.newBuilder()
        .setCollectionName("rpg-chunks")
        .addAllVector(queryVector)
        .setFilter(filter)
        .setLimit(topK)
        .setScoreThreshold(threshold)
        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
        .build()
    ).get().stream()
        .map(this::toScoredChunk)
        .toList();
}
```

### Cross-Rulebook Search (no rulebookId filter)

```java
public List<ScoredChunk> searchAll(List<Float> queryVector, int topK, float threshold) {
    return client.searchAsync(SearchPoints.newBuilder()
        .setCollectionName("rpg-chunks")
        .addAllVector(queryVector)
        .setLimit(topK)
        .setScoreThreshold(threshold)
        .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
        .build()
    ).get().stream()
        .map(this::toScoredChunk)
        .toList();
}
```

## HNSW Tuning Guide

| Scenario                            | `m` | `ef_construct` | `ef` (search) | Notes                             |
| ----------------------------------- | --- | -------------- | ------------- | --------------------------------- |
| Dev / small rulebook (<5k vectors)  | 16  | 100            | 64            | Defaults work fine                |
| Prod / full rulebook (>50k vectors) | 32  | 200            | 128           | Better recall, ~2x index time     |
| Latency-critical (<20ms P99)        | 16  | 100            | 32            | Lower recall, acceptable for chat |

## Payload Size Budget

| Field         | Max Size | Notes                                                   |
| ------------- | -------- | ------------------------------------------------------- |
| `text`        | 2KB      | Chunk text. Longer = more context but slower retrieval. |
| `chunk_id`    | 36 chars | UUID                                                    |
| `document_id` | 36 chars | UUID                                                    |
| `rulebook_id` | 64 chars | Slug: `dnd-5e-phb`, `pathfinder-2e-core`                |

## Operational Checklist

- [ ] Collection exists and `rulebook_id` payload index created before first ingestion
- [ ] Vector dimension matches embedding model (768 for `nomic-embed-text`)
- [ ] Qdrant dashboard accessible at `localhost:6333/dashboard` in dev
- [ ] Search with `rulebookId = "dnd-5e-phb"` never returns Pathfinder chunks
- [ ] Upsert is idempotent: same `chunk_id` updates, doesn't duplicate
