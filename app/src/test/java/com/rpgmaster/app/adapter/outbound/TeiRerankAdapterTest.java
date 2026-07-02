package com.rpgmaster.app.adapter.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.rpgmaster.app.config.RerankProperties;
import com.rpgmaster.domain.SourceChunk;

class TeiRerankAdapterTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private TeiRerankAdapter adapter;

    private static SourceChunk chunk(String id, String text) {
        return new SourceChunk(id, text, 1, 0.5f, "dnd-5e-phb");
    }

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://tei.test");
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", "http://tei.test");
        adapter = new TeiRerankAdapter(builder, props);
    }

    @Test
    @DisplayName("reorders candidates by TEI score and truncates to topK")
    void reordersAndTruncates() {
        // TEI returns index->score; candidate 2 is most relevant, then 0, then 1.
        server.expect(requestTo("http://tei.test/rerank"))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(
                      "[{\"index\":2,\"score\":0.9},{\"index\":0,\"score\":0.5},{\"index\":1,\"score\":0.1}]",
                      MediaType.APPLICATION_JSON));

        var candidates = List.of(chunk("a", "alpha"), chunk("b", "bravo"), chunk("c", "charlie"));
        var result = adapter.rerank("q", candidates, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).chunkId()).isEqualTo("c");   // index 2, score 0.9
        assertThat(result.get(0).score()).isEqualTo(0.9f);
        assertThat(result.get(1).chunkId()).isEqualTo("a");   // index 0, score 0.5
        server.verify();
    }

    @Test
    @DisplayName("returns empty for empty candidates without calling TEI")
    void emptyCandidates() {
        var result = adapter.rerank("q", List.of(), 5);
        assertThat(result).isEmpty();
        server.verify(); // no request expected
    }

    @Test
    @DisplayName("fails loud on TEI error (no silent fallback)")
    void failsLoudOnError() {
        server.expect(requestTo("http://tei.test/rerank")).andRespond(withServerError());
        var candidates = List.of(chunk("a", "alpha"));
        assertThatThrownBy(() -> adapter.rerank("q", candidates, 5))
                .isInstanceOf(RuntimeException.class);
        server.verify();
    }
}
