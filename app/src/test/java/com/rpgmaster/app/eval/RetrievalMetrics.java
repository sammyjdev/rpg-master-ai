package com.rpgmaster.app.eval;

import java.util.List;

public final class RetrievalMetrics {

    public record RetrievedPage(String rulebookId, int pageNumber) {}

    private RetrievalMetrics() {}

    public static double recallAtK(List<RetrievedPage> retrieved,
                                   List<RelevantPage> relevant, int k) {
        if (relevant == null || relevant.isEmpty()) {
            return 0.0;
        }
        List<RetrievedPage> topK = retrieved.subList(0, Math.min(k, retrieved.size()));
        long found = relevant.stream()
                .filter(rel -> topK.stream().anyMatch(got -> matches(got, rel)))
                .count();
        return (double) found / relevant.size();
    }

    public static double reciprocalRank(List<RetrievedPage> retrieved,
                                        List<RelevantPage> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            RetrievedPage got = retrieved.get(i);
            if (relevant.stream().anyMatch(rel -> matches(got, rel))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private static boolean matches(RetrievedPage got, RelevantPage rel) {
        return got.pageNumber() == rel.pageNumber()
                && got.rulebookId().equals(rel.rulebookId());
    }
}
