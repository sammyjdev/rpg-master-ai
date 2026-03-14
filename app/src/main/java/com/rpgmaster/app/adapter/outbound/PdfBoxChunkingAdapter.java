package com.rpgmaster.app.adapter.outbound;

import com.rpgmaster.app.application.port.ChunkingPort;
import com.rpgmaster.domain.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Splits page text into overlapping fixed-size chunks using a whitespace-split
 * token approximation. Follows the RAG Architect decision tree:
 * fixed-size 200 tokens / 40 overlap for standard rulebook pages.
 *
 * <p>Token count is approximated as whitespace-split word count — close enough
 * for English RPG text with nomic-embed-text's 512-token context window.
 */
@Component
public class PdfBoxChunkingAdapter implements ChunkingPort {

    private static final Logger log = LoggerFactory.getLogger(PdfBoxChunkingAdapter.class);

    /**
     * Minimum word count for a chunk to be considered useful.
     * Chunks below this threshold after cleaning are discarded.
     */
    private static final int MIN_CHUNK_WORDS = 10;

    /**
     * Keywords that identify fan-translation boilerplate lines.
     * If a line (split by newlines) contains 3+ of these keywords,
     * the entire line is stripped. This is resilient to OCR mangling
     * because it matches normalized lowercase words, not exact phrases.
     */
    private static final Set<String> BOILERPLATE_KEYWORDS = Set.of(
            "material", "elaborado", "destinado",
            "impressao", "venda", "expressamente", "proibidas"
    );

    /**
     * Minimum number of boilerplate keywords a line must contain
     * to be considered boilerplate and stripped.
     */
    private static final int BOILERPLATE_KEYWORD_THRESHOLD = 3;

    /**
     * {@inheritDoc}
     *
     * <p>Uses SequencedCollection for ordered chunk window access.
     */
    @Override
    public List<Chunk> chunk(String text, int pageNumber, String documentId,
                             String rulebookId, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Strip boilerplate/watermark text before chunking
        var cleanedText = stripBoilerplate(text);
        if (cleanedText.isBlank()) {
            log.debug("Page {}: empty after boilerplate removal, skipping", pageNumber);
            return List.of();
        }

        // Whitespace-split word array — approximates tokens for RPG prose
        var words = cleanedText.split("\\s+");
        var chunks = new ArrayList<Chunk>();

        int start = 0;
        while (start < words.length) {
            int end = Math.min(start + config.maxTokens(), words.length);
            var chunkWords = words;

            // Build chunk text from word window
            var chunkText = buildText(chunkWords, start, end);
            int tokenCount = end - start;

            var trimmedChunk = chunkText.trim();

            // Discard chunks that are too short to be useful after cleaning
            if (tokenCount >= MIN_CHUNK_WORDS && !trimmedChunk.isBlank()) {
                chunks.add(new Chunk(
                        UUID.randomUUID().toString(),
                        trimmedChunk,
                        tokenCount,
                        pageNumber,
                        documentId,
                        rulebookId,
                        Map.of("chunkIndex", String.valueOf(chunks.size()))
                ));
            }

            // Advance by (maxTokens - overlapTokens) to create overlap window
            int advance = config.maxTokens() - config.overlapTokens();
            start += advance;
        }

        // SequencedCollection — demonstrates Java 21 idiom
        if (!chunks.isEmpty()) {
            log.debug("Page {}: {} chunk(s), first={} tokens, last={} tokens",
                    pageNumber, chunks.size(),
                    chunks.getFirst().tokenCount(),
                    chunks.getLast().tokenCount());
        }

        return chunks;
    }

    private String buildText(String[] words, int start, int end) {
        var sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (i > start) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * Removes known boilerplate/watermark text from PDF page content.
     * Fan-translated PDFs often have disclaimers on every page that
     * contaminate embeddings and degrade retrieval quality.
     *
     * <p>Uses keyword frequency per line instead of exact regex to handle
     * OCR-mangled text (e.g., "fSTf MATERIAl FOI ElABORADO").
     */
    private String stripBoilerplate(String text) {
        var lines = text.split("\\n");
        var cleaned = new StringBuilder();
        for (var line : lines) {
            if (!isBoilerplateLine(line)) {
                cleaned.append(line).append('\n');
            }
        }
        // Collapse multiple blank lines left after stripping
        return cleaned.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * Checks if a line is boilerplate by counting how many boilerplate
     * keywords appear in its normalized (lowercase, accent-stripped) form.
     */
    private boolean isBoilerplateLine(String line) {
        var normalized = normalizeForComparison(line);
        long matches = BOILERPLATE_KEYWORDS.stream()
                .filter(normalized::contains)
                .count();
        return matches >= BOILERPLATE_KEYWORD_THRESHOLD;
    }

    /**
     * Normalizes text for boilerplate comparison: lowercase, strip accents,
     * remove non-letter characters. This makes matching resilient to OCR noise.
     */
    private String normalizeForComparison(String text) {
        // Decompose accented characters, strip combining marks, lowercase
        var normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        return normalized.replaceAll("[\\p{M}]", "").toLowerCase();
    }
}
