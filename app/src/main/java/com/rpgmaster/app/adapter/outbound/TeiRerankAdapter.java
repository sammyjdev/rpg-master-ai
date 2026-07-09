package com.rpgmaster.app.adapter.outbound;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.rpgmaster.app.application.port.RerankPort;
import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

/**
 * Reranker adapter backed by HuggingFace TEI ({@code POST /rerank}) serving a
 * cross-encoder ({@code bge-reranker-v2-m3}). TEI returns [{index, score}, …];
 * this remaps indices back to the original {@link SourceChunk}s, overwrites the
 * score with the reranker score, and keeps the top-K. Fails loud on TEI error.
 */
@Component
public class TeiRerankAdapter implements RerankPort {

    private static final Logger log = LoggerFactory.getLogger(TeiRerankAdapter.class);

    private final RestClient restClient;

    public TeiRerankAdapter(RestClient.Builder builder, RerankProperties props) {
        this.restClient = builder.baseUrl(props.baseUrl()).build();
    }

    @Override
    public List<SourceChunk> rerank(String query, List<SourceChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        var texts = candidates.stream().map(SourceChunk::text).toList();
        RerankResult[] results = restClient.post()
                .uri("/rerank")
                .body(Map.of("query", query, "texts", texts))
                .retrieve()
                .body(RerankResult[].class);
        if (results == null) {
            throw new IllegalStateException("TEI /rerank returned no body");
        }
        log.debug("Reranked {} candidates -> keeping top {}", candidates.size(), topK);
        return Arrays.stream(results)
                .sorted(Comparator.comparingDouble(RerankResult::score).reversed())
                .limit(topK)
                .map(r -> {
                    SourceChunk c = candidates.get(r.index());
                    return new SourceChunk(c.chunkId(), c.text(), c.pageNumber(), (float) r.score(), c.rulebookId());
                })
                .toList();
    }

    /** TEI /rerank response element. */
    record RerankResult(int index, double score) {}
}
