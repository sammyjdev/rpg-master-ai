package com.rpgmaster.app.adapter.outbound;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.rpgmaster.app.application.port.LlmPort;
import com.rpgmaster.domain.LlmResult;

import reactor.core.publisher.Flux;

/**
 * LLM adapter backed by Spring AI's {@link ChatModel}.
 * In the {@code local} profile this calls Ollama ({@code qwen2.5:7b}).
 *
 * <p>Language handling is delegated entirely to the system prompt
 * ({@code prompts/rag-system.st}) which instructs the model to reply in the
 * same language as the question. No client-side language detection is needed.
 */
@Component
public class SpringAiLlmAdapter implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmAdapter.class);

    private final ChatModel chatModel;

    public SpringAiLlmAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** {@inheritDoc} */
    @Override
    public LlmResult generateBlocking(String systemPrompt, String userPrompt, List<String> context) {
        var augmentedUser = buildAugmentedPrompt(userPrompt, context);
        log.debug("Calling LLM (blocking) with {} context chunks", context.size());

        var prompt = new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(augmentedUser)
        ));

        var response = chatModel.call(prompt);
        var text = response.getResult().getOutput().getText();

        var usage = response.getMetadata().getUsage();
        var tokensUsed = usage != null ? (int) usage.getTotalTokens() : 0;

        log.debug("LLM response: {} tokens used", tokensUsed);
        return new LlmResult(text, tokensUsed);
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
        return """
                Context from rulebook:
                %s

                Question: %s
                """.formatted(contextText, question);
    }
}
