package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.rpgmaster.app.config.PromptConfig;

class PromptConfigParseTest {

    @Test
    void parsesVersionHeaderAndStripsItFromBody() {
        var parsed = PromptConfig.parse("# version: v1.0\nYou are an expert.\nLine 2.");

        assertThat(parsed.version()).isEqualTo("v1.0");
        assertThat(parsed.body()).isEqualTo("You are an expert.\nLine 2.");
    }

    @Test
    void supportsExtraWhitespaceInHeader() {
        var parsed = PromptConfig.parse("#   version:    v2.3-rc1  \nBody.");

        assertThat(parsed.version()).isEqualTo("v2.3-rc1");
        assertThat(parsed.body()).isEqualTo("Body.");
    }

    @Test
    void fallsBackToUnversionedWhenHeaderIsMissing() {
        var parsed = PromptConfig.parse("You are an expert.\nNo header here.");

        assertThat(parsed.version()).isEqualTo("unversioned");
        assertThat(parsed.body()).isEqualTo("You are an expert.\nNo header here.");
    }

    @Test
    void treatsNonHeaderCommentLineAsBody() {
        // A comment that is not a version header is preserved as part of the prompt body.
        var parsed = PromptConfig.parse("# not a version line\nBody.");

        assertThat(parsed.version()).isEqualTo("unversioned");
        assertThat(parsed.body()).isEqualTo("# not a version line\nBody.");
    }
}
