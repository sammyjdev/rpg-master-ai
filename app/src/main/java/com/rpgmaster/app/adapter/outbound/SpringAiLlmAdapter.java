package com.rpgmaster.app.adapter.outbound;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.LlmPort;

import reactor.core.publisher.Flux;

/**
 * LLM adapter backed by Spring AI's {@link ChatModel}.
 * In the {@code local} profile this calls Ollama ({@code llama3.2:3b}).
 * In the {@code prod} profile swap for BedrockLlmAdapter.
 *
 * <p>The RAG system prompt uses a text block (Java 21) and is defined inline here.
 * For Increment 2+, extract to {@code src/main/resources/prompts/rag-system.st}.
 */
@Component
public class SpringAiLlmAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmAdapter.class);

    /** Unambiguous PT-only words — none are valid English words. */
    private static final Set<String> PT_STOPWORDS = Set.of(
            // Question words & conjunctions
            "qual", "quais", "quando", "porque", "como", "quanto", "quantos", "quem", "que",
            // Common verbs (not English words)
            "pode", "foi", "faz", "ter", "seria", "funciona",
            // Pronouns, articles & prepositions (not English words)
            "uma", "seu", "isso", "esse", "essa", "minha", "meu", "pelo", "pela",
            "mas", "ou", "das", "nos",
            // Adverbs & particles
            "está", "não", "também", "então", "você",
            // RPG domain terms (PT-only)
            "descanso", "personagem", "feitiço", "magia", "combate",
            "habilidade", "aventura", "monstro", "regras", "rolar", "dado", "classe",
            "fogo", "dano", "conjurar", "ataque"
    );

    private final ChatModel chatModel;

    public SpringAiLlmAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** {@inheritDoc} */
    @Override
    public Flux<String> generateStream(String systemPrompt, String userPrompt, List<String> context) {
        var augmentedUser = buildAugmentedPrompt(userPrompt, context);
        log.debug("Calling LLM with {} context chunks", context.size());

        var prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(augmentedUser)
        ));

        return chatModel.stream(prompt)
                .mapNotNull(response -> {
                    if (response.getResult() == null) return null;
                    var output = response.getResult().getOutput();
                    return output == null ? null : output.getText();
                })
                .filter(text -> text != null && !text.isEmpty());
    }

    private String buildAugmentedPrompt(String question, List<String> context) {
        var contextText = String.join("\n\n---\n\n", context);
        var detectedLanguage = isLikelyPortuguese(question) ? "Portuguese" : "English";
        return """
                Context from rulebook:
                %s

                Question: %s
                IMPORTANT: Answer in %s.
                """.formatted(contextText, question, detectedLanguage);
    }

    /**
     * Detects Portuguese by counting Unicode characters typical of Brazilian Portuguese
     * (accented vowels, cedilla, tilde) and distinctive PT-only stopwords.
     * Avoids English false positives ("do", "a", "no", "que" in some contexts).
     */
    private boolean isLikelyPortuguese(String text) {
        // Fast path: any accented PT character strongly indicates Portuguese
        for (char c : text.toCharArray()) {
            if (c == '\u00e3' || c == '\u00f5' || c == '\u00e7' // ã õ ç
                    || c == '\u00e0' || c == '\u00e2' || c == '\u00ea' // à â ê
                    || c == '\u00e9' || c == '\u00ed' || c == '\u00fa' || c == '\u00f3') { // é í ú ó
                return true;
            }
        }
        // Unambiguous PT-only words (none of these are English words)
        return Arrays.stream(text.toLowerCase().split("\\W+")).anyMatch(PT_STOPWORDS::contains);
    }
}
