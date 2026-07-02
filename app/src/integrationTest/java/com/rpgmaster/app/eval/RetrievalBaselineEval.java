package com.rpgmaster.app.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rpgmaster.app.application.port.EmbeddingPort;
import com.rpgmaster.app.application.port.VectorStorePort;
import com.rpgmaster.domain.SourceChunk;

/**
 * Baseline retrieval eval. NOT a CI test — run explicitly against a live stack
 * (docker-compose Qdrant + Ollama, corpus already ingested):
 *   ./gradlew :app:eval
 * Writes eval/reports/retrieval-<millis>.md.
 */
@SpringBootTest
@ActiveProfiles({"local", "eval"})
class RetrievalBaselineEval {

    private static final int[] K_SWEEP = {3, 5, 8, 10};
    private static final float THRESHOLD = 0.3f;

    @Autowired EmbeddingPort embeddingPort;
    @Autowired VectorStorePort vectorStorePort;

    @Test
    void recordBaseline() throws IOException {
        var cases = GoldenSet.load(getClass().getResourceAsStream("/golden-qa.json"));
        int maxK = java.util.Arrays.stream(K_SWEEP).max().getAsInt();

        // per-k accumulators
        double[] recallSum = new double[K_SWEEP.length];
        double[] rrSum = new double[K_SWEEP.length];

        for (GoldenCase c : cases) {
            var vector = embeddingPort.embed(c.question());
            // search once at maxK per rulebook of the case's first relevant page
            String rulebook = c.relevantPages().get(0).rulebookId();
            List<SourceChunk> hits = vectorStorePort.search(rulebook, vector, maxK, THRESHOLD);

            List<RetrievalMetrics.RetrievedPage> retrieved = new ArrayList<>();
            for (SourceChunk h : hits) {
                retrieved.add(new RetrievalMetrics.RetrievedPage(h.rulebookId(), h.pageNumber()));
            }

            for (int i = 0; i < K_SWEEP.length; i++) {
                recallSum[i] += RetrievalMetrics.recallAtK(retrieved, c.relevantPages(), K_SWEEP[i]);
                rrSum[i] += RetrievalMetrics.reciprocalRank(
                        retrieved.subList(0, Math.min(K_SWEEP[i], retrieved.size())),
                        c.relevantPages());
            }
        }

        int n = cases.size();
        StringBuilder md = new StringBuilder();
        md.append("# Retrieval baseline\n\n");
        md.append("Cases: ").append(n).append(", threshold: ").append(THRESHOLD).append("\n\n");
        md.append("| k | mean recall@k | mean MRR |\n|---|---|---|\n");
        for (int i = 0; i < K_SWEEP.length; i++) {
            md.append("| ").append(K_SWEEP[i]).append(" | ")
              .append(String.format("%.3f", recallSum[i] / n)).append(" | ")
              .append(String.format("%.3f", rrSum[i] / n)).append(" |\n");
        }

        Path dir = Path.of("..", "eval", "reports");
        Files.createDirectories(dir);
        Path out = dir.resolve("retrieval-" + System.currentTimeMillis() + ".md");
        Files.writeString(out, md.toString());
        System.out.println("Wrote " + out.toAbsolutePath());
        System.out.println(md);
    }
}
