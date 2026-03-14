package com.rpgmaster.app.application.port;

import java.nio.file.Path;
import java.util.List;

/**
 * Port for reading text content from PDF documents.
 * Increment 1: reads from local filesystem. Increment 2+: AWS S3.
 * Adapter: LocalFileDocumentStorageAdapter (Increment 1), S3DocumentStorageAdapter (Increment 2+).
 */
public interface DocumentStoragePort {

    /**
     * Reads all pages from a PDF file and returns them as a list of page texts.
     * The list index corresponds to page number (0-indexed internally; adapters expose 1-indexed).
     *
     * @param path path to the PDF file on the local filesystem
     * @return list of page texts, one entry per PDF page (in order)
     */
    List<String> readPages(Path path);
}
