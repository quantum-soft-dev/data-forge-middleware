package com.bitbi.dfm.comparison.infrastructure;

import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.DiffService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of {@link DiffService} using the java-diff-utils library.
 *
 * <p>This implementation uses the Myers diff algorithm to compare file contents
 * line-by-line and generates a structured JSON representation of the differences.
 *
 * <p>The diff output is stored in JSONB format compatible with react-diff-viewer-continued
 * for frontend rendering.
 */
@Service
public class DiffServiceImpl implements DiffService {

    private static final Logger log = LoggerFactory.getLogger(DiffServiceImpl.class);
    private final ObjectMapper objectMapper;

    public DiffServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DiffResult generateDiff(
        String currentContent,
        String targetContent,
        String currentFileName,
        String targetFileName
    ) {
        if (currentContent == null) {
            throw new IllegalArgumentException("Current content cannot be null");
        }

        log.debug("Generating diff: current={}, target={}", currentFileName, targetFileName);

        try {
            // Convert content to lines
            List<String> currentLines = splitIntoLines(currentContent);
            List<String> targetLines = targetContent != null ? splitIntoLines(targetContent) : Collections.emptyList();

            // Determine change type
            ChangeType changeType = determineChangeType(currentLines, targetLines, targetContent);

            // If unchanged, return minimal result
            if (changeType == ChangeType.UNCHANGED) {
                return new DiffResult(
                    ChangeType.UNCHANGED,
                    null,  // No diff stored for unchanged files
                    0,
                    0,
                    0L
                );
            }

            // Generate diff using Myers algorithm
            Patch<String> patch = DiffUtils.diff(targetLines, currentLines);

            // Convert to structured JSON
            String diffJson = convertPatchToJson(patch, currentFileName, targetFileName != null ? targetFileName : "(new file)");

            // Calculate statistics
            int additions = calculateAdditions(patch);
            int deletions = calculateDeletions(patch);
            long changeSize = calculateChangeSize(additions, deletions, currentContent);

            log.debug("Diff generated: type={}, additions={}, deletions={}, size={}",
                changeType, additions, deletions, changeSize);

            return new DiffResult(changeType, diffJson, additions, deletions, changeSize);

        } catch (Exception e) {
            log.error("Failed to generate diff for current={}, target={}",
                currentFileName, targetFileName, e);
            throw new DiffGenerationException(
                "Failed to generate diff: " + e.getMessage(),
                e
            );
        }
    }

    /**
     * Splits file content into lines, preserving empty lines.
     */
    private List<String> splitIntoLines(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(content.split("\\r?\\n", -1));
    }

    /**
     * Determines the change type based on file contents.
     */
    private ChangeType determineChangeType(List<String> currentLines, List<String> targetLines, String targetContent) {
        if (targetContent == null || targetContent.isEmpty()) {
            return ChangeType.ADDED;
        }
        if (currentLines.equals(targetLines)) {
            return ChangeType.UNCHANGED;
        }
        return ChangeType.MODIFIED;
    }

    /**
     * Converts a diff Patch object to structured JSON format.
     *
     * <p>JSON structure:
     * <pre>
     * {
     *   "hunks": [
     *     {
     *       "oldStart": 1,
     *       "oldLines": 10,
     *       "newStart": 1,
     *       "newLines": 12,
     *       "changes": [
     *         {"type": "UNCHANGED", "lineNumber": 1, "content": "line 1"},
     *         {"type": "REMOVED", "lineNumber": 2, "content": "old line"},
     *         {"type": "ADDED", "lineNumber": 2, "content": "new line"}
     *       ]
     *     }
     *   ],
     *   "oldFileName": "file.txt",
     *   "newFileName": "file.txt"
     * }
     * </pre>
     */
    private String convertPatchToJson(Patch<String> patch, String newFileName, String oldFileName) {
        Map<String, Object> diffStructure = new LinkedHashMap<>();
        List<Map<String, Object>> hunks = new ArrayList<>();

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Map<String, Object> hunk = new LinkedHashMap<>();
            hunk.put("oldStart", delta.getSource().getPosition() + 1);  // 1-based indexing
            hunk.put("oldLines", delta.getSource().size());
            hunk.put("newStart", delta.getTarget().getPosition() + 1);
            hunk.put("newLines", delta.getTarget().size());

            List<Map<String, Object>> changes = new ArrayList<>();

            // Add removed lines
            int lineNum = delta.getSource().getPosition() + 1;
            for (String line : delta.getSource().getLines()) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("type", "REMOVED");
                change.put("lineNumber", lineNum++);
                change.put("content", line);
                changes.add(change);
            }

            // Add added lines
            lineNum = delta.getTarget().getPosition() + 1;
            for (String line : delta.getTarget().getLines()) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("type", "ADDED");
                change.put("lineNumber", lineNum++);
                change.put("content", line);
                changes.add(change);
            }

            hunk.put("changes", changes);
            hunks.add(hunk);
        }

        diffStructure.put("hunks", hunks);
        diffStructure.put("oldFileName", oldFileName);
        diffStructure.put("newFileName", newFileName);

        try {
            return objectMapper.writeValueAsString(diffStructure);
        } catch (JsonProcessingException e) {
            throw new DiffGenerationException("Failed to serialize diff to JSON", e);
        }
    }

    /**
     * Calculates the number of lines added in the patch.
     */
    private int calculateAdditions(Patch<String> patch) {
        return patch.getDeltas().stream()
            .mapToInt(delta -> delta.getTarget().getLines().size())
            .sum();
    }

    /**
     * Calculates the number of lines deleted in the patch.
     */
    private int calculateDeletions(Patch<String> patch) {
        return patch.getDeltas().stream()
            .mapToInt(delta -> delta.getSource().getLines().size())
            .sum();
    }

    /**
     * Estimates the total size of changes in bytes.
     *
     * <p>This is an approximation based on line counts and average line length.
     */
    private long calculateChangeSize(int additions, int deletions, String currentContent) {
        int totalLines = currentContent.split("\\r?\\n").length;
        if (totalLines == 0) {
            return 0L;
        }

        // Average bytes per line
        long avgBytesPerLine = currentContent.getBytes().length / totalLines;

        // Total changed bytes = (additions + deletions) * average line size
        return (additions + deletions) * avgBytesPerLine;
    }
}
