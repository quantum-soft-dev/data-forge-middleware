package com.bitbi.dfm.plugin.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the pairing invariant of {@code generateSqlForBatch} (issue #260): every exit that writes
 * {@code SQL_GENERATION_STARTED} also writes a terminal audit entry.
 *
 * <p>The behavioral coverage of each STARTED-writing exit lives in
 * {@code SqlGenerationServiceTest.DeltaV2Routing}:
 * <ul>
 *   <li>winner → {@code logSqlGenerationCompleted}</li>
 *   <li>empty diff → {@code logSqlGenerationCompletedNoChanges}</li>
 *   <li>throw after the attempt was announced → {@code logSqlGenerationFailed}</li>
 *   <li>lost unique claim → {@code logSqlGenerationAdopted}</li>
 * </ul>
 * This class holds the inventory itself, so a future exit cannot drop the adopted terminal by
 * editing only the adopt branch's comments. Proven by mutation: deleting the
 * {@code logSqlGenerationAdopted} call (or returning before it) fails
 * {@link #adoptBranchWritesTheAdoptedTerminalBeforeReturning()}.</p>
 *
 * <p><strong>Documented exceptions</strong> — exits that never write STARTED, so they are outside
 * the pairing:
 * <ul>
 *   <li>baseline batch / first-batch-becomes-baseline / {@code batchData == null} — no attempt</li>
 *   <li>{@code MemoryPressureAbortedException} — refused <em>above</em> STARTED (#181), pinned by
 *       {@code SqlGenerationStreamingTest}</li>
 *   <li>{@code SemaphoreTimeoutAbortedException} — refused before {@code doGenerateSqlForBatch}
 *       (#261)</li>
 * </ul>
 * There is no documented exception that writes STARTED and then returns without a terminal.</p>
 */
@DisplayName("generateSqlForBatch — STARTED is always paired with a terminal (#260)")
class SqlGenerationStartedPairingTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java");

    @Test
    @DisplayName("doGenerateSqlForBatch names every terminal writer, including ADOPTED")
    void doGenerateSqlForBatchNamesEveryTerminalWriter() throws IOException {
        String method = doGenerateSqlForBatchSource();

        assertThat(method).contains("logSqlGenerationStarted");
        // logSqlGenerationCompleted( is a prefix of logSqlGenerationCompletedNoChanges(, so
        // rename the no-changes call before asserting the winner's writer is still present.
        String withoutNoChanges = method.replace("logSqlGenerationCompletedNoChanges", "NO_CHANGES");
        assertThat(withoutNoChanges)
                .as("winner")
                .contains("logSqlGenerationCompleted(");
        assertThat(method)
                .as("empty diff")
                .contains("logSqlGenerationCompletedNoChanges(");
        assertThat(method)
                .as("failure after STARTED")
                .contains("logSqlGenerationFailed(");
        assertThat(method)
                .as("lost unique claim")
                .contains("logSqlGenerationAdopted(");
    }

    @Test
    @DisplayName("the adopt early-return writes ADOPTED before it returns")
    void adoptBranchWritesTheAdoptedTerminalBeforeReturning() throws IOException {
        String method = doGenerateSqlForBatchSource();
        int adoptedIf = method.indexOf("if (claim.adopted())");
        assertThat(adoptedIf)
                .as("the adopted early-return must still exist — it is the path #246/#260 own")
                .isGreaterThanOrEqualTo(0);

        int adoptedLog = method.indexOf("logSqlGenerationAdopted(", adoptedIf);
        int adoptedReturn = method.indexOf("return Optional.of(claim.generation())", adoptedIf);
        assertThat(adoptedLog)
                .as("the adopt branch must write SQL_GENERATION_ADOPTED")
                .isGreaterThan(adoptedIf);
        assertThat(adoptedReturn)
                .as("the adopt branch must not return before writing the terminal")
                .isGreaterThan(adoptedLog);
    }

    private static String doGenerateSqlForBatchSource() throws IOException {
        String source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        int start = source.indexOf("private Optional<PluginSqlGeneration> doGenerateSqlForBatch(");
        assertThat(start)
                .as("doGenerateSqlForBatch must still be the body generateSqlForBatch delegates to")
                .isGreaterThanOrEqualTo(0);
        int brace = source.indexOf('{', start);
        int depth = 0;
        int end = brace;
        for (int i = brace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        return source.substring(start, end + 1);
    }
}
