package com.rpgmaster.app.unit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rpgmaster.app.adapter.outbound.PdfBoxChunkingAdapter;
import com.rpgmaster.app.application.port.ChunkingPort.ChunkConfig;
import com.rpgmaster.domain.Chunk;

/**
 * Unit tests for PdfBoxChunkingAdapter.
 * Pure Java — no Spring context required.
 */
class PdfBoxChunkingAdapterTest {

    private final PdfBoxChunkingAdapter adapter = new PdfBoxChunkingAdapter();

    private static final String DOC_ID = "doc-1";
    private static final String RULEBOOK_ID = "dnd-5e-phb";
    private static final String WORD = "word ";

    @Test
    @DisplayName("Empty text returns empty chunk list")
    void emptyTextReturnsNoChunks() {
        var result = adapter.chunk("", 1, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Blank text returns empty chunk list")
    void blankTextReturnsNoChunks() {
        var result = adapter.chunk("   \n\t  ", 1, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Short text (< maxTokens) produces exactly one chunk")
    void shortTextProducesOneChunk() {
        var text = "Fireball is a 3rd-level evocation spell that deals 8d6 fire damage.";
        var result = adapter.chunk(text, 5, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());

        assertThat(result).hasSize(1);

        var chunk = result.getFirst(); // Java 21 SequencedCollection
        assertThat(chunk.pageNumber()).isEqualTo(5);
        assertThat(chunk.documentId()).isEqualTo(DOC_ID);
        assertThat(chunk.rulebookId()).isEqualTo(RULEBOOK_ID);
        assertThat(chunk.id()).isNotBlank();
        assertThat(chunk.text()).isNotBlank();
    }

    @Test
    @DisplayName("Long text produces multiple overlapping chunks")
    void longTextProducesMultipleChunks() {
        // Generate text with 600 words (> 512 token default)
        var words = WORD.repeat(600).trim();
        var config = new ChunkConfig(512, 64);

        var result = adapter.chunk(words, 1, DOC_ID, RULEBOOK_ID, config);

        assertThat(result).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Each chunk has token count within configured max")
    void tokenCountWithinMaxTokens() {
        var words = WORD.repeat(1200).trim();
        var config = new ChunkConfig(512, 64);

        var result = adapter.chunk(words, 1, DOC_ID, RULEBOOK_ID, config);

        assertThat(result).allSatisfy(chunk ->
                assertThat(chunk.tokenCount()).isLessThanOrEqualTo(config.maxTokens())
        );
    }

    @Test
    @DisplayName("Page number is propagated to all chunks")
    void pageNumberPropagatedToAllChunks() {
        var words = WORD.repeat(600).trim();
        var result = adapter.chunk(words, 42, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());

        assertThat(result).allSatisfy(chunk ->
                assertThat(chunk.pageNumber()).isEqualTo(42)
        );
    }

    @Test
    @DisplayName("All chunks have non-null, non-blank UUIDs")
    void allChunksHaveUniqueIds() {
        var words = WORD.repeat(600).trim();
        var result = adapter.chunk(words, 1, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());

        var ids = result.stream().map(Chunk::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).allSatisfy(id -> assertThat(id).isNotBlank());
    }

    @Test
    @DisplayName("Last chunk uses Java 21 SequencedCollection getLast()")
    void sequencedCollectionGetLastWorks() {
        var words = WORD.repeat(600).trim();
        var result = adapter.chunk(words, 1, DOC_ID, RULEBOOK_ID, ChunkConfig.defaults());

        // Java 21 idiom — SequencedCollection
        assertThat(result.getLast()).isNotNull();
        assertThat(result.getLast().pageNumber()).isEqualTo(1);
    }
}
