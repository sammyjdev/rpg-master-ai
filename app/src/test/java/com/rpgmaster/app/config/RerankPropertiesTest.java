package com.rpgmaster.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RerankPropertiesTest {

    @Test
    @DisplayName("holds values when top-n is positive")
    void validValues() {
        var props = new RerankProperties(true, 30, "BAAI/bge-reranker-v2-m3", "http://localhost:8090");
        assertThat(props.enabled()).isTrue();
        assertThat(props.topN()).isEqualTo(30);
        assertThat(props.model()).isEqualTo("BAAI/bge-reranker-v2-m3");
        assertThat(props.baseUrl()).isEqualTo("http://localhost:8090");
    }

    @Test
    @DisplayName("rejects non-positive top-n")
    void rejectsBadTopN() {
        assertThatThrownBy(() -> new RerankProperties(true, 0, "m", "http://x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rpg.rerank.top-n");
    }
}
