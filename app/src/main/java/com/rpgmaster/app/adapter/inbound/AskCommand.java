package com.rpgmaster.app.adapter.inbound;

import com.rpgmaster.app.application.QueryUseCase;
import com.rpgmaster.app.config.RetrievalProperties;
import com.rpgmaster.domain.QueryRequest;
import com.rpgmaster.domain.SourceChunk;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

/**
 * Spring Shell command for querying a rulebook via the RAG pipeline.
 *
 * <p>Usage:
 * <pre>{@code
 * rpg:> ask --question "What is the range of a Fireball spell?" --rulebook dnd-5e-phb
 * }</pre>
 */
@ShellComponent
public class AskCommand {

    /** Sentinel used in {@link ShellOption#defaultValue()} to mean "fall back to {@link RetrievalProperties}". */
    private static final String USE_CONFIGURED_DEFAULT = "-1";

    private final QueryUseCase queryUseCase;
    private final RetrievalProperties retrieval;

    public AskCommand(QueryUseCase queryUseCase, RetrievalProperties retrieval) {
        this.queryUseCase = queryUseCase;
        this.retrieval = retrieval;
    }

    @ShellMethod(value = "Ask a question about a rulebook", key = "ask")
    public String ask(
            @ShellOption(help = "Your natural language question") String question,
            @ShellOption(defaultValue = ShellOption.NULL,
                    help = "Rulebook ID to search (omit to search all rulebooks)") String rulebook,
            @ShellOption(defaultValue = USE_CONFIGURED_DEFAULT,
                    help = "Number of context chunks to retrieve (default: rpg.retrieval.top-k)") int topK,
            @ShellOption(defaultValue = USE_CONFIGURED_DEFAULT,
                    help = "Minimum similarity threshold 0.0-1.0 (default: rpg.retrieval.similarity-threshold)") float threshold
    ) {
        var effectiveTopK = topK < 0 ? retrieval.topK() : topK;
        var effectiveThreshold = threshold < 0 ? retrieval.similarityThreshold() : threshold;
        var request = new QueryRequest(question, rulebook, effectiveTopK, effectiveThreshold);
        var result = queryUseCase.query(request);

        var sb = new StringBuilder();
        sb.append("\n━━━ Answer ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(result.answer());
        sb.append("\n\n━━━ Sources (%d chunks) ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                .formatted(result.sources().size()));

        for (SourceChunk source : result.sources()) {
            sb.append("  • [Page %d | score=%.2f | %s] %s%n"
                    .formatted(source.pageNumber(), source.score(),
                            source.rulebookId(),
                            truncate(source.text(), 120)));
        }

        sb.append("\n  Latency: %dms\n".formatted(result.latencyMs()));

        return sb.toString();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
