package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent migration documentation")
class MigrationDocumentationConsistencyTest {

    private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");
    private static final List<Path> AGENT_DOCUMENTS = List.of(Path.of("AGENTS.md"), Path.of("CLAUDE.md"));
    private static final Pattern MIGRATION_FILE = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Pattern MIGRATION_POINTER = Pattern.compile(
            "(?:Current at|Migrations current at) \\*\\*V(\\d+)\\*\\*[,;] "
                    + "next(?: migration)? is \\*\\*V(\\d+)\\*\\*");

    @Test
    @DisplayName("matches both migration pointers to the migration directory")
    void shouldMatchBothMigrationPointersToMigrationDirectory() throws IOException {
        int currentVersion = currentMigrationVersion();
        int nextVersion = currentVersion + 1;

        for (Path document : AGENT_DOCUMENTS) {
            String content = Files.readString(document);
            Matcher matcher = MIGRATION_POINTER.matcher(content);
            int pointerCount = 0;

            while (matcher.find()) {
                pointerCount++;
                assertThat(Integer.parseInt(matcher.group(1)))
                        .as("current migration pointer in %s", document)
                        .isEqualTo(currentVersion);
                assertThat(Integer.parseInt(matcher.group(2)))
                        .as("next migration pointer in %s", document)
                        .isEqualTo(nextVersion);
            }

            assertThat(pointerCount)
                    .as("migration pointers in %s", document)
                    .isEqualTo(2);
        }
    }

    private int currentMigrationVersion() throws IOException {
        try (Stream<Path> migrations = Files.list(MIGRATION_DIRECTORY)) {
            return migrations
                    .map(path -> path.getFileName().toString())
                    .map(MIGRATION_FILE::matcher)
                    .filter(Matcher::matches)
                    .mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
                    .max()
                    .orElseThrow(() -> new AssertionError("No Flyway migrations found in " + MIGRATION_DIRECTORY));
        }
    }
}
