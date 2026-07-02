package com.rpgmaster.app.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    private static final String RB = "dnd-5e-phb";

    private RetrievalMetrics.RetrievedPage r(int page) { return new RetrievalMetrics.RetrievedPage(RB, page); }
    private RelevantPage rel(int page) { return new RelevantPage(RB, page); }

    @Test
    void recallIsOneWhenRelevantPageIsWithinK() {
        var retrieved = List.of(r(10), r(241), r(5));   // relevant at position 2
        var relevant = List.of(rel(241));

        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 3)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 1)).isEqualTo(0.0);
    }

    @Test
    void recallIsFractionOfRelevantPagesFound() {
        var retrieved = List.of(r(241), r(99));
        var relevant = List.of(rel(241), rel(300));      // only one of two present

        assertThat(RetrievalMetrics.recallAtK(retrieved, relevant, 8)).isEqualTo(0.5);
    }

    @Test
    void reciprocalRankUsesFirstRelevantPosition() {
        var retrieved = List.of(r(10), r(20), r(241));   // first relevant at rank 3
        var relevant = List.of(rel(241));

        assertThat(RetrievalMetrics.reciprocalRank(retrieved, relevant))
                .isEqualTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void reciprocalRankIsZeroWhenNoRelevantRetrieved() {
        assertThat(RetrievalMetrics.reciprocalRank(List.of(r(1), r(2)), List.of(rel(999))))
                .isEqualTo(0.0);
    }
}
