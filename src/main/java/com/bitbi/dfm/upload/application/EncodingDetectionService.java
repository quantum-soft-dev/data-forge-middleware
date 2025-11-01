package com.bitbi.dfm.upload.application;

import com.ibm.icu.text.CharsetDetector;
import com.ibm.icu.text.CharsetMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Service for detecting character encoding of CSV files.
 *
 * Uses ICU4J CharsetDetector to identify encoding with 90%+ accuracy.
 * Falls back to UTF-8 if confidence is low (<50%).
 *
 * Common encodings handled:
 * - UTF-8 (modern standard)
 * - Windows-1252 (Excel default on Windows)
 * - ISO-8859-1 (legacy systems)
 */
@Service
public class EncodingDetectionService {

    private static final Logger logger = LoggerFactory.getLogger(EncodingDetectionService.class);

    private static final int DETECTION_BUFFER_SIZE = 8192; // 8KB sample for detection
    private static final int MIN_CONFIDENCE_THRESHOLD = 50;

    /**
     * Detects the character encoding of an input stream and returns a Reader.
     *
     * The input stream is read and reset, so it can be consumed after detection.
     *
     * @param inputStream The input stream to detect encoding for (must support mark/reset)
     * @param filename The filename for logging purposes
     * @return A Reader configured with the detected charset
     * @throws IOException If detection fails or stream doesn't support mark/reset
     */
    public Reader detectEncodingAndCreateReader(InputStream inputStream, String filename) throws IOException {
        // Wrap in BufferedInputStream to ensure mark/reset support
        BufferedInputStream buffered = inputStream instanceof BufferedInputStream
            ? (BufferedInputStream) inputStream
            : new BufferedInputStream(inputStream);

        buffered.mark(DETECTION_BUFFER_SIZE);

        try {
            // Read sample for detection
            byte[] buffer = new byte[DETECTION_BUFFER_SIZE];
            int bytesRead = buffered.read(buffer);

            // Reset stream for actual consumption
            buffered.reset();

            if (bytesRead <= 0) {
                logger.warn("Empty file detected: {}, using UTF-8 default", filename);
                return new InputStreamReader(buffered, StandardCharsets.UTF_8);
            }

            // Detect charset using ICU4J
            CharsetDetector detector = new CharsetDetector();
            detector.setText(buffer);
            CharsetMatch match = detector.detect();

            if (match == null) {
                logger.warn("No charset match for file: {}, using UTF-8 default", filename);
                return new InputStreamReader(buffered, StandardCharsets.UTF_8);
            }

            Charset detectedCharset;
            int confidence = match.getConfidence();

            if (confidence >= MIN_CONFIDENCE_THRESHOLD) {
                try {
                    detectedCharset = Charset.forName(match.getName());
                    logger.info("Detected encoding for file '{}': {} (confidence: {}%)",
                        filename, detectedCharset.name(), confidence);
                } catch (Exception e) {
                    logger.warn("Unsupported charset '{}' for file: {}, using UTF-8 default",
                        match.getName(), filename, e);
                    detectedCharset = StandardCharsets.UTF_8;
                }
            } else {
                logger.info("Low confidence ({}%) for file '{}', using UTF-8 default",
                    confidence, filename);
                detectedCharset = StandardCharsets.UTF_8;
            }

            return new InputStreamReader(buffered, detectedCharset);

        } catch (IOException e) {
            logger.error("Error detecting encoding for file: {}", filename, e);
            throw e;
        }
    }

    /**
     * Detects the charset of an input stream without creating a Reader.
     *
     * Useful for validation or logging without consuming the stream.
     *
     * @param inputStream The input stream to detect encoding for (must support mark/reset)
     * @param filename The filename for logging purposes
     * @return The detected Charset, or UTF-8 as fallback
     * @throws IOException If detection fails
     */
    public Charset detectCharset(InputStream inputStream, String filename) throws IOException {
        BufferedInputStream buffered = inputStream instanceof BufferedInputStream
            ? (BufferedInputStream) inputStream
            : new BufferedInputStream(inputStream);

        buffered.mark(DETECTION_BUFFER_SIZE);

        try {
            byte[] buffer = new byte[DETECTION_BUFFER_SIZE];
            int bytesRead = buffered.read(buffer);
            buffered.reset();

            if (bytesRead <= 0) {
                return StandardCharsets.UTF_8;
            }

            CharsetDetector detector = new CharsetDetector();
            detector.setText(buffer);
            CharsetMatch match = detector.detect();

            if (match != null && match.getConfidence() >= MIN_CONFIDENCE_THRESHOLD) {
                try {
                    return Charset.forName(match.getName());
                } catch (Exception e) {
                    logger.warn("Unsupported charset: {}, using UTF-8", match.getName());
                }
            }

            return StandardCharsets.UTF_8;

        } finally {
            buffered.reset();
        }
    }
}
