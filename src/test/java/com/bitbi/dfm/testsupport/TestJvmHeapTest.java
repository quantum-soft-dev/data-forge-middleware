package com.bitbi.dfm.testsupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader behind the {@code build.gradle.kts} guard of issue #207, over synthetic sources.
 *
 * <p>A guard that reads a file wrongly is worse than no guard: it reports a ceiling that is not
 * there, or fails a build that declares one correctly. Every hole this reader has to close is a
 * shape the real file already contains — prose about the settings in the comments right beside
 * them, string literals holding braces, and a second assignment on a narrower task. So the reader
 * is asserted directly rather than only through the file it happens to read today, the
 * {@code LockWaitBoundTest} precedent.</p>
 */
@DisplayName("Reading the test-JVM heap declaration out of build.gradle.kts (#207)")
class TestJvmHeapTest {

    @Test
    @DisplayName("a size literal is read the way -Xmx reads it")
    void shouldParseSizeLiterals() {
        assertEquals(2L * 1024 * 1024 * 1024, TestJvmHeap.parseSize("2g"));
        assertEquals(2L * 1024 * 1024 * 1024, TestJvmHeap.parseSize("2G"));
        assertEquals(2048L * 1024 * 1024, TestJvmHeap.parseSize("2048m"));
        assertEquals(1536L * 1024, TestJvmHeap.parseSize("1536k"));
        assertEquals(1234L, TestJvmHeap.parseSize(" 1234 "));
    }

    @Test
    @DisplayName("a literal that is not a size fails by name instead of reading as bytes")
    void shouldRefuseAnUnreadableSizeLiteral() {
        // "2 GiB" read as a bare number would be 2 bytes, and a ceiling of 2 bytes reads as a
        // ceiling right up to the moment the JVM refuses to start.
        assertThrows(IllegalArgumentException.class, () -> TestJvmHeap.parseSize("2 GiB"));
        assertThrows(IllegalArgumentException.class, () -> TestJvmHeap.parseSize(""));
        assertThrows(IllegalArgumentException.class, () -> TestJvmHeap.parseSize("2t"));
        // A value the JVM itself refuses to start on must not read as a ceiling.
        assertThrows(IllegalArgumentException.class, () -> TestJvmHeap.parseSize("2gb"));
        assertThrows(IllegalArgumentException.class,
                () -> TestJvmHeap.parseSize("99999999999999999999999g"));
    }

    @Test
    @DisplayName("a declaration named only in a comment is not a declaration")
    void shouldNotReadCommentsAsDeclarations() {
        String script = """
                // Nothing sets maxHeapSize = "8g" here, this line only talks about it.
                /* and neither does maxHeapSize = "16g" in a block comment */
                tasks.withType<Test> {
                    useJUnitPlatform()
                }
                """;

        assertTrue(TestJvmHeap.declaredHeapSizes(script).isEmpty(),
                "a comment mentioning the setting was read as setting it");
    }

    @Test
    @DisplayName("every declaration is reported, so a narrower override cannot hide behind the first")
    void shouldReportEveryDeclaration() {
        String script = """
                tasks.withType<Test> {
                    maxHeapSize = "2g"
                }
                tasks.named<Test>("test") {
                    maxHeapSize = "512m"
                }
                """;

        assertEquals(List.of("2g", "512m"), TestJvmHeap.declaredHeapSizes(script));
    }

    @Test
    @DisplayName("the block body is the block's own, and an unbalanced brace in a literal is not a brace")
    void shouldExtractTheBlockBody() {
        String script = """
                tasks.withType<Test> {
                    systemProperty("odd", "}")
                    jvmArgs("-XX:+ExitOnOutOfMemoryError")
                    maxHeapSize = "2g"
                    doFirst {
                        mkdir("reports")
                    }
                }
                tasks.named<Test>("test") {
                    maxHeapSize = "512m"
                }
                """;

        String block = TestJvmHeap.allTestTasksBlock(script);

        assertFalse(block.contains("512m"),
                "the unbalanced brace inside a string literal ended the block early, so the guard "
                        + "would read the next task's settings as this block's: " + block);
        assertTrue(block.contains("-XX:+ExitOnOutOfMemoryError"),
                "the flags are string literals and have to survive the read: " + block);
        assertTrue(block.contains("\"2g\""), "the block stopped before its own declaration: " + block);
        assertTrue(block.contains("mkdir"), "a nested block ended the match early: " + block);
    }

    @Test
    @DisplayName("a block that mentions a flag only in prose does not declare it")
    void shouldStripCommentsFromTheBlockBody() {
        String script = """
                tasks.withType<Test> {
                    // -XX:+HeapDumpOnOutOfMemoryError is deliberately not set here.
                    maxHeapSize = "2g"
                }
                """;

        String block = TestJvmHeap.allTestTasksBlock(script);

        assertFalse(block.contains("-XX:+HeapDumpOnOutOfMemoryError"),
                "a sentence about the flag was read as the flag: " + block);
    }

    @Test
    @DisplayName("a quote inside a character literal does not open a string literal")
    void shouldNotReadACharacterLiteralAsAString() {
        // Without char-literal handling the '"' opens a string that runs to the next quote and
        // swallows the declaration between them, so the guard reports a block with no ceiling.
        String script = """
                tasks.withType<Test> {
                    val quote = '"'
                    maxHeapSize = "2g"
                    jvmArgs("-XX:+ExitOnOutOfMemoryError")
                }
                """;

        String block = TestJvmHeap.allTestTasksBlock(script);

        assertTrue(block.contains("maxHeapSize"), "the character literal swallowed the block: " + block);
        assertTrue(block.contains("-XX:+ExitOnOutOfMemoryError"),
                "the character literal swallowed the flags: " + block);
        assertEquals(List.of("2g"), TestJvmHeap.declaredHeapSizes(script));
    }

    @Test
    @DisplayName("a nested literal inside a template does not desynchronise the read")
    void shouldReadAStringTemplateAsOneLiteral() {
        // The shape build.gradle.kts already has: a template expression holding literals of its
        // own. Pairing the inner opening quote with the outer one inverts every quote after it, and
        // the two hazards below are then live rather than theoretical — a brace inside a nested
        // literal is left in "code" with its partner masked, which runs the block match past the
        // closing brace, and a // inside one blanks the rest of its line, declaration included.
        String script = """
                tasks.withType<Test> {
                    systemProperty("odd", "${dir("{").path}")
                    jvmArgs("-XX:HeapDumpPath=${dir("// reports").get().asFile.path}"); maxHeapSize = "2g"
                    jvmArgs("-XX:+ExitOnOutOfMemoryError")
                }
                tasks.named<Test>("test") {
                    maxHeapSize = "512m"
                }
                """;

        String block = TestJvmHeap.allTestTasksBlock(script);

        assertNotNull(block, "the template's braces ended the block before it began");
        assertFalse(block.contains("512m"),
                "an unpaired brace left in code by the desynchronised read ran the block match past "
                        + "its own closing brace and swallowed the next task: " + block);
        assertTrue(block.contains("-XX:+ExitOnOutOfMemoryError"),
                "the read lost the flags declared after the template: " + block);
        // Both declarations of the fixture, in order — the narrower one is still seen, which is
        // what lets the guard refuse a build file that leaves a sibling task on the default.
        assertEquals(List.of("2g", "512m"), TestJvmHeap.declaredHeapSizes(script),
                "a // inside a template's nested literal blanked the rest of its line");
    }

    @Test
    @DisplayName("a // inside a string literal is a URL, not a comment")
    void shouldNotReadASlashInsideALiteralAsAComment() {
        // Blanking to end of line here would take the declaration on the same line with it.
        String script = """
                tasks.withType<Test> {
                    systemProperty("docs", "https://example.test/x"); maxHeapSize = "2g"
                    jvmArgs("-XX:+ExitOnOutOfMemoryError")
                }
                """;

        assertEquals(List.of("2g"), TestJvmHeap.declaredHeapSizes(script),
                "the // of a URL blanked the rest of the line, declaration included");
        assertTrue(TestJvmHeap.allTestTasksBlock(script).contains("-XX:+ExitOnOutOfMemoryError"));
    }

    @Test
    @DisplayName("Kotlin block comments nest, so the first close does not end them")
    void shouldReadNestedBlockComments() {
        String script = """
                /* outer /* inner */ maxHeapSize = "8g" */
                tasks.withType<Test> {
                    maxHeapSize = "2g"
                }
                """;

        assertEquals(List.of("2g"), TestJvmHeap.declaredHeapSizes(script),
                "the inner close ended the comment and a commented-out declaration was counted");
    }

    @Test
    @DisplayName("no block at all is reported as absence, not as an empty body")
    void shouldReportAMissingBlock() {
        assertNull(TestJvmHeap.allTestTasksBlock("plugins { java }\n"));
    }
}
