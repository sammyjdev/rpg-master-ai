package com.rpgmaster.app.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class GoldenSetTest {

    @Test
    void loadsAndValidatesTheBundledGoldenSet() {
        var in = getClass().getResourceAsStream("/golden-qa.json");
        List<GoldenCase> cases = GoldenSet.load(in);

        assertThat(cases).hasSizeGreaterThanOrEqualTo(3);
        assertThat(cases.get(0).id()).isEqualTo("gq-001");
        assertThat(cases.get(0).relevantPages())
                .containsExactly(new RelevantPage("dnd-5e-mm", 21));
    }

    @Test
    void rejectsCaseWithNoRelevantPages() {
        var bad = """
            [{"id":"x","question":"q","expectedAnswer":"a","relevantPages":[]}]
            """;
        var in = new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> GoldenSet.load(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }

    @Test
    void rejectsCaseSpanningMultipleRulebooks() {
        var bad = """
            [{"id":"multi-rb","question":"q","expectedAnswer":"a","relevantPages":[
                {"rulebookId":"dnd-5e-phb","pageNumber":1},
                {"rulebookId":"dnd-5e-mm","pageNumber":2}
            ]}]
            """;
        var in = new ByteArrayInputStream(bad.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> GoldenSet.load(in))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multi-rb");
    }
}
