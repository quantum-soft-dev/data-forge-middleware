package com.bitbi.dfm.config;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Java file does not keep an import nothing in it names (issue #352).
 *
 * <p>{@code javac} 25 dropped {@code -Xlint:unused}, so an unused import is invisible to
 * the compile and to CI. The scan is the text of {@code src}: comments are blanked,
 * string and text-block literals are kept, and a simple name counts as used only when
 * it appears as its own identifier. A hit that is only the last segment of a fully
 * qualified name (the character before it is {@code '.'}) is not a use — that is how
 * {@code import …OAuth2Error} survived a scan that treated {@code new org….OAuth2Error}
 * as a reference.</p>
 */
class UnusedImportConventionTest {

    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)\\.(\\w+);");

    @Test
    @DisplayName("every non-star import is named somewhere in its file")
    void everyImportIsUsed() throws IOException {
        List<String> unused = new ArrayList<>();
        Path root = RunOwnedScratch.projectRoot();
        for (String tree : List.of("src/main/java", "src/test/java")) {
            Path start = root.resolve(tree);
            if (!Files.isDirectory(start)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(start)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path ->
                        unused.addAll(unusedIn(root.relativize(path).toString(), read(path))));
            }
        }
        assertThat(unused)
                .withFailMessage("unused imports (javac 25 does not report them):%n%s",
                        String.join("\n", unused))
                .isEmpty();
    }

    private static List<String> unusedIn(String relative, String source) {
        String body = stripComments(source);
        String[] lines = body.split("\n", -1);
        StringBuilder outsideImports = new StringBuilder();
        List<String> imports = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = IMPORT.matcher(line.strip());
            if (matcher.matches()) {
                imports.add(relative + ": " + matcher.group(2) + " (" + matcher.group(1) + "." + matcher.group(2) + ")");
                outsideImports.append('\n');
            } else {
                outsideImports.append(line).append('\n');
            }
        }
        String rest = outsideImports.toString();
        List<String> unused = new ArrayList<>();
        for (String imported : imports) {
            String simple = imported.substring(imported.indexOf(": ") + 2, imported.indexOf(" ("));
            if (!used(rest, simple)) {
                unused.add(imported);
            }
        }
        return unused;
    }

    private static boolean used(String rest, String simple) {
        Pattern name = Pattern.compile("\\b" + Pattern.quote(simple) + "\\b");
        Matcher matcher = name.matcher(rest);
        while (matcher.find()) {
            if (matcher.start() == 0 || rest.charAt(matcher.start() - 1) != '.') {
                return true;
            }
        }
        return false;
    }

    /**
     * Blanks {@code //} and block comments. String, character and text-block literals stay,
     * so a name that exists only inside one of them is not reported — a false negative, and
     * the direction that does not demand deleting a live import.
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' && i + 2 < source.length() && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                int end = source.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    out.append(source.substring(i));
                    break;
                }
                out.append(source, i, end + 3);
                i = end + 3;
            } else if (c == '"') {
                i = copyLiteral(source, out, i, '"');
            } else if (c == '\'') {
                i = copyLiteral(source, out, i, '\'');
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                if (end < 0) {
                    break;
                }
                out.append('\n');
                i = end + 1;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    break;
                }
                String comment = source.substring(i, end);
                out.append("\n".repeat((int) comment.chars().filter(ch -> ch == '\n').count()));
                i = end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int copyLiteral(String source, StringBuilder out, int start, char quote) {
        out.append(quote);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            out.append(c);
            if (c == '\\' && i + 1 < source.length()) {
                out.append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
