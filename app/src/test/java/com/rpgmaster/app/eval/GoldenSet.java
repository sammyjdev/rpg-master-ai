package com.rpgmaster.app.eval;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class GoldenSet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldenSet() {}

    public static List<GoldenCase> load(InputStream json) {
        List<GoldenCase> cases;
        try {
            cases = MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, GoldenCase.class));
        } catch (Exception e) {
            throw new IllegalArgumentException("golden-qa.json is not valid JSON", e);
        }
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("golden-qa.json is empty");
        }
        for (GoldenCase c : cases) {
            validate(c);
        }
        return cases;
    }

    private static void validate(GoldenCase c) {
        String id = c.id();
        if (isBlank(id)) {
            throw new IllegalArgumentException("golden case has blank id");
        }
        if (isBlank(c.question()) || isBlank(c.expectedAnswer())) {
            throw new IllegalArgumentException("golden case '" + id + "' has blank question or expectedAnswer");
        }
        if (c.relevantPages() == null || c.relevantPages().isEmpty()) {
            throw new IllegalArgumentException("golden case '" + id + "' has no relevantPages");
        }
        for (RelevantPage p : c.relevantPages()) {
            if (isBlank(p.rulebookId()) || p.pageNumber() < 1) {
                throw new IllegalArgumentException("golden case '" + id + "' has an invalid relevantPage");
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
