package com.rpgmaster.app.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Exports golden-qa.json into GNOMON's dataset format at eval/gnomon/dataset.json.
 *
 * Field names and file shape mirror GNOMON's real schema, not a guess:
 *   - EvalCase (~/dev/gnomon-eval/src/gnomon/domain/models.py): id, question,
 *     expected_answer, expected_contexts (list[str], non-empty).
 *   - The dataset file itself is a JSON array, not JSONL
 *     (~/dev/gnomon-eval/src/gnomon/dataset/loader.py does json.loads(...) and
 *     requires a non-empty list).
 *
 * expected_contexts is schema-required but not read by the v1 judge prompt
 * (~/dev/gnomon-eval/src/gnomon/judge/prompts.py builds CONTEXTS from the live
 * RagResponse returned by the target, never from the case). A synthesized
 * "<rulebookId> page <n>" string per relevant page satisfies the schema
 * without needing raw chunk text in the golden set.
 *
 * Run: ./gradlew :app:integrationTest --tests 'com.rpgmaster.app.eval.GnomonDatasetExport'
 */
class GnomonDatasetExport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void export() throws IOException {
        var cases = GoldenSet.load(getClass().getResourceAsStream("/golden-qa.json"));

        ArrayNode array = MAPPER.createArrayNode();
        for (GoldenCase c : cases) {
            var node = MAPPER.createObjectNode();
            node.put("id", c.id());
            node.put("question", c.question());
            node.put("expected_answer", c.expectedAnswer());
            var contexts = node.putArray("expected_contexts");
            for (RelevantPage p : c.relevantPages()) {
                contexts.add(p.rulebookId() + " page " + p.pageNumber());
            }
            array.add(node);
        }

        Path out = Path.of("..", "eval", "gnomon", "dataset.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(array));
        System.out.println("Wrote " + out.toAbsolutePath());
    }
}
