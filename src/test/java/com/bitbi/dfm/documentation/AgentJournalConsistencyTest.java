package com.bitbi.dfm.documentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "Recent Changes" journal reaches both agent instruction files (issue #278, part A).
 *
 * <p>The repository's convention is that an entry ships with its code into {@code CLAUDE.md} and
 * {@code AGENTS.md} alike. Nothing held it: the omission is invisible in the diff of a PR that
 * writes to one file, so a review habit cannot catch it. #205 fixed a single miss and answered
 * "no guard needed"; four days later three more entries were missing, each written by its own PR
 * — one miss is a slip, four in a week is a mechanism.</p>
 *
 * <h2>Why an anchor, and why it is a constant rather than a convention</h2>
 *
 * <p>The two journals are genuinely unequal, which is what #205 took as the argument against a
 * guard: {@code AGENTS.md} stopped carrying entries for a stretch ({@code 033}–{@code 042},
 * {@code tag-driven-dev-deploy}, {@code plugin-secret-reveal},
 * {@code agent-migration-doc-consistency}) and later resumed. But entries are only ever
 * <em>prepended</em> and never removed, so the boundary of that gap — the newest slug of the
 * abandoned block, {@link #ANCHOR} — cannot move again. It is one constant set once, not a line
 * somebody has to maintain, which is the same shape {@link MigrationDocumentationConsistencyTest}
 * already has over these two files. Above the anchor the predicate is mechanical and has no false
 * positive.</p>
 *
 * <p>Deliberately one-directional: {@code AGENTS.md} is a subset of {@code CLAUDE.md} by
 * construction (its entries are condensed rewrites of the same slugs), so the reverse containment
 * is not asserted — an entry appearing only in {@code AGENTS.md} would be a different mistake and
 * is caught by the duplicate check below no more than by this one.</p>
 */
@DisplayName("Agent journal consistency (issue #278)")
class AgentJournalConsistencyTest {

    private static final Path AGENTS = Path.of("AGENTS.md");
    private static final Path CLAUDE = Path.of("CLAUDE.md");

    /**
     * The newest slug of the block {@code AGENTS.md} never carried. Immovable: entries are only
     * prepended, so nothing can ever be inserted below it.
     */
    private static final String ANCHOR = "042-parquet-phase-metrics";

    private static final String SECTION = "## Recent Changes";
    private static final Pattern ENTRY = Pattern.compile("^- ([A-Za-z0-9][A-Za-z0-9._-]*): ");

    @Test
    @DisplayName("every CLAUDE.md entry above the anchor is also in AGENTS.md")
    void shouldCarryEveryRecentEntryIntoBothJournals() throws IOException {
        List<String> claude = journalSlugs(CLAUDE);
        Set<String> agents = Set.copyOf(journalSlugs(AGENTS));

        int anchorAt = claude.indexOf(ANCHOR);
        assertThat(anchorAt)
                .as("the anchor '%s' must exist in %s — without it this guard asserts nothing",
                        ANCHOR, CLAUDE)
                .isNotNegative();

        List<String> missing = new ArrayList<>(claude.subList(0, anchorAt));
        missing.removeIf(agents::contains);

        assertThat(missing)
                .as("entries written to %s but never to %s — the journal ships with the code, "
                        + "so add the condensed form of each to %s", CLAUDE, AGENTS, AGENTS)
                .isEmpty();
    }

    @Test
    @DisplayName("the shared entries appear in the same order in both journals")
    void shouldKeepTheSharedEntriesInOneOrder() throws IOException {
        List<String> claude = journalSlugs(CLAUDE);
        List<String> agents = journalSlugs(AGENTS);

        List<String> shared = new ArrayList<>(agents);
        shared.retainAll(Set.copyOf(claude));
        List<String> expected = new ArrayList<>(claude);
        expected.retainAll(Set.copyOf(agents));

        assertThat(shared)
                .as("%s lists its entries in a different order than %s — both are newest-first, so "
                        + "a backfilled entry belongs where its %s neighbours put it; inserting it "
                        + "anywhere else is the same defect one step quieter",
                        AGENTS, CLAUDE, CLAUDE)
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("neither journal carries the same entry twice")
    void shouldNotRepeatAnEntryWithinOneJournal() throws IOException {
        for (Path document : List.of(AGENTS, CLAUDE)) {
            List<String> slugs = journalSlugs(document);
            Set<String> seen = new LinkedHashSet<>();
            List<String> duplicates = new ArrayList<>();
            for (String slug : slugs) {
                if (!seen.add(slug)) {
                    duplicates.add(slug);
                }
            }

            assertThat(slugs)
                    .as("no journal entry parsed out of %s — the section heading or the entry "
                            + "shape changed and this guard has gone blind", document)
                    .isNotEmpty();
            assertThat(duplicates)
                    .as("entries repeated in %s — two copies drift apart, and a reader resolves "
                            + "the contradiction by accident (#205 found exactly that)", document)
                    .isEmpty();
        }
    }

    /**
     * The slugs of one document's "Recent Changes" section, newest first, in file order.
     *
     * <p>Only top-level list items are entries; continuation lines are indented, so the anchored
     * pattern skips them. The section runs to the next {@code ## } heading or to end of file.</p>
     */
    private static List<String> journalSlugs(Path document) throws IOException {
        List<String> slugs = new ArrayList<>();
        boolean inSection = false;
        for (String line : Files.readAllLines(document)) {
            if (line.strip().equals(SECTION)) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            if (line.startsWith("## ")) {
                break;
            }
            Matcher matcher = ENTRY.matcher(line);
            if (matcher.find()) {
                slugs.add(matcher.group(1));
            }
        }
        return slugs;
    }
}
