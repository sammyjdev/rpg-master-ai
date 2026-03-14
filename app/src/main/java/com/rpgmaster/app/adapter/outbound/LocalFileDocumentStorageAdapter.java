package com.rpgmaster.app.adapter.outbound;

import com.rpgmaster.app.application.port.DocumentStoragePort;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads PDF pages from the local filesystem using Apache PDFBox 3.x.
 *
 * <p>Note: PDFBox 3.x uses {@code Loader.loadPDF} instead of the deprecated
 * {@code PDDocument.load}. Some internal PDFBox operations use synchronized
 * blocks, which can pin Virtual Threads. For the MVP load, this is acceptable.
 * Monitor with {@code -Djdk.tracePinnedThreads=full} under load.
 */
@Component
public class LocalFileDocumentStorageAdapter implements DocumentStoragePort {

    private static final Logger log = LoggerFactory.getLogger(LocalFileDocumentStorageAdapter.class);

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if the file does not exist or is not a PDF
     */
    @Override
    public List<String> readPages(Path path) {
        log.info("Reading PDF pages from {}", path);

        try (var document = org.apache.pdfbox.Loader.loadPDF(path.toFile())) {
            return extractPages(document);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read PDF at path: " + path, e);
        }
    }

    private List<String> extractPages(PDDocument document) throws IOException {
        var stripper = new PDFTextStripper();
        var pages = new ArrayList<String>(document.getNumberOfPages());

        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            pages.add(stripper.getText(document));
        }

        log.info("Extracted {} pages from PDF", pages.size());
        return pages;
    }
}
