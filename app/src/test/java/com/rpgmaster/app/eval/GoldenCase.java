package com.rpgmaster.app.eval;

import java.util.List;

public record GoldenCase(
        String id,
        String question,
        String expectedAnswer,
        List<RelevantPage> relevantPages) {}
