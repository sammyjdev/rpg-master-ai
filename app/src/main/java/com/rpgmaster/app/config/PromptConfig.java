package com.rpgmaster.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads prompt templates from {@code src/main/resources/prompts/}.
 *
 * <p>Each template begins with a {@code # version: vX.Y} comment line. The
 * version is exposed as a separate bean so audit logs and metrics can correlate
 * answers with the exact prompt revision in use. The version line is stripped
 * from the runtime prompt — the model never sees it.
 */
@Configuration
public class PromptConfig {

    private static final Pattern VERSION_HEADER = Pattern.compile("^#\\s*version:\\s*(\\S+)\\s*\\R?");

    private final ParsedPrompt ragSystem;

    public PromptConfig() throws IOException {
        var raw = new ClassPathResource("prompts/rag-system.st")
                .getContentAsString(StandardCharsets.UTF_8);
        this.ragSystem = parse(raw);
    }

    /** RAG system prompt body, with the version header stripped. */
    @Bean
    public String ragSystemPrompt() {
        return ragSystem.body();
    }

    /** Version identifier from the {@code # version: vX.Y} header — used in audit logs. */
    @Bean
    public String ragPromptVersion() {
        return ragSystem.version();
    }

    /** Visible for testing — extracts the version header and returns the body without it. */
    public static ParsedPrompt parse(String raw) {
        var matcher = VERSION_HEADER.matcher(raw);
        if (matcher.lookingAt()) {
            var version = matcher.group(1);
            var body = raw.substring(matcher.end());
            return new ParsedPrompt(version, body);
        }
        return new ParsedPrompt("unversioned", raw);
    }

    public record ParsedPrompt(String version, String body) {}
}
