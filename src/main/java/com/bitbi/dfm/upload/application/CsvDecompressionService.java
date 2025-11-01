package com.bitbi.dfm.upload.application;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Service for decompressing gzip-compressed CSV files.
 *
 * Uses Apache Commons Compress for streaming decompression with:
 * - 8KB buffer (low memory footprint)
 * - Support for concatenated gzip members
 * - Streaming API (no intermediate files)
 */
@Service
public class CsvDecompressionService {

    private static final Logger logger = LoggerFactory.getLogger(CsvDecompressionService.class);

    /**
     * Decompresses a gzip-compressed input stream if the filename indicates compression.
     *
     * If the filename ends with .gz, returns a GzipCompressorInputStream.
     * Otherwise, returns the original stream unchanged.
     *
     * @param inputStream The input stream to potentially decompress
     * @param filename The filename to check for .gz extension
     * @return A decompressed InputStream if .gz, otherwise the original stream
     * @throws IOException If decompression fails
     */
    public InputStream decompressIfNeeded(InputStream inputStream, String filename) throws IOException {
        if (isGzipCompressed(filename)) {
            logger.debug("Decompressing gzip file: {}", filename);
            // decompressConcatenated=true handles multiple gzip members in one file
            return new GzipCompressorInputStream(inputStream, true);
        }

        logger.debug("File not gzip-compressed, using raw stream: {}", filename);
        return inputStream;
    }

    /**
     * Checks if a filename indicates gzip compression.
     *
     * @param filename The filename to check
     * @return true if filename ends with .gz (case-insensitive), false otherwise
     */
    public boolean isGzipCompressed(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        return filename.toLowerCase().endsWith(".gz");
    }

    /**
     * Extracts the base CSV filename by removing .gz extension.
     *
     * Examples:
     * - "sales-2024.csv.gz" → "sales-2024.csv"
     * - "data.csv" → "data.csv" (unchanged)
     * - "file.txt.gz" → "file.txt"
     *
     * @param filename The filename to process
     * @return The filename without .gz extension
     */
    public String getBaseFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return filename;
        }

        if (isGzipCompressed(filename)) {
            return filename.substring(0, filename.length() - 3); // Remove ".gz"
        }

        return filename;
    }
}
