package com.bitbi.dfm.integration;

import com.bitbi.dfm.plugin.application.BitBiV2ChangesService;
import com.bitbi.dfm.plugin.application.BitBiV2ChangesService.ChangesBatch;
import com.bitbi.dfm.plugin.application.BitBiV2ChangesService.ChangesPage;
import com.bitbi.dfm.plugin.application.BitBiV2ChangesService.ChangesTable;
import com.bitbi.dfm.plugin.application.BitBiV2ChangesService.ManifestStatus;
import com.bitbi.dfm.plugin.domain.BitBiChangesCursor;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
import com.bitbi.dfm.site.application.SiteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Bit BI v2 changes feed (issue #388) on the real SQL: order, the head of the queue, the
 * epoch (stamped and unstamped), {@code afterSeq} within the consumer's epoch, the cursor, and the
 * resolution of a batch {@code fileId}.
 *
 * <p>Each test seeds an account and a site of its own inside the test transaction, so nothing here
 * is visible to the site-blind queue workers of this or any other context, and nothing outlives the
 * method.</p>
 */
@Transactional
@DisplayName("Bit BI v2 changes feed on the real SQL (#388)")
class BitBiV2ChangesIntegrationTest extends BaseIntegrationTest {

    /** Inside the #380 enqueue horizon (168 h) whenever the suite runs. */
    private static final LocalDateTime T0 = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
            .minusHours(6);

    @Autowired
    private BitBiV2ChangesService service;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID account;
    private UUID site;

    @BeforeEach
    void anAccountAndASiteAtEpoch4() {
        account = UUID.randomUUID();
        site = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, email, name, is_active, created_at, updated_at)
                VALUES (?, ?, 'issue 388', true, ?, ?)
                """, account, account + "@example.com", T0, T0);
        insertSite(site, account);
        jdbc.update("INSERT INTO site_sync_state (site_id, baseline_epoch, last_applied_seq) VALUES (?, 4, 10000)",
                site);
    }

    // --- order and manifest ------------------------------------------------------------------------

    @Test
    @DisplayName("Batches come in batch order, not in the order their files were published")
    void batchOrderNotReadyAtOrder() {
        UUID first = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("orders", 100L), 4L);
        UUID second = batch(2, "DELTA", "COMPLETED", 101, 110, Map.of("orders", 10L), 4L);
        UUID third = batch(3, "DELTA", "COMPLETED", 111, 120, Map.of("orders", 10L), 4L);
        // Published in the reverse order: the third first, the snapshot last.
        artifact(third, "orders", "READY", 10L, T0.plusHours(4));
        artifact(second, "orders", "READY", 10L, T0.plusHours(5));
        artifact(first, "orders", "READY", 100L, T0.plusHours(6));

        ChangesPage page = service.changes(account, site, null, 0L, null, 50);

        assertThat(page.batches()).extracting(ChangesBatch::batchId).containsExactly(first, second, third);
        assertThat(page.epoch()).isEqualTo(4L);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("A batch's manifest: ready with a fileId and the file's rows, abandoned and missing without one")
    void manifest() {
        UUID batch = batch(1, "DELTA", "COMPLETED", 5_023_012, 5_023_845,
                Map.of("nsftrns", 834L, "nsfcard", 25L, "tmp_x", 4L), 4L);
        artifact(batch, "nsftrns", "READY", 834L, T0.plusHours(2));
        artifact(batch, "nsfcard", "ABANDONED", null, T0.plusHours(3));

        ChangesBatch listed = service.changes(account, site, null, 0L, null, 50).batches().getFirst();

        assertThat(listed.epoch()).isEqualTo(4L);
        assertThat(listed.sessionMode()).isEqualTo("delta");
        assertThat(listed.firstSeq()).isEqualTo(5_023_012L);
        assertThat(listed.lastSeq()).isEqualTo(5_023_845L);
        assertThat(listed.readyAt()).as("the last table to settle — here the abandoned one")
                .isEqualTo(T0.plusHours(3).toInstant(ZoneOffset.UTC));
        assertThat(listed.tables()).containsExactly(
                new ChangesTable("nsfcard", ManifestStatus.ABANDONED, null, 25L),
                new ChangesTable("nsftrns", ManifestStatus.READY,
                        new BitBiFileId.BatchFile(site, batch, "nsftrns").encode(), 834L),
                new ChangesTable("tmp_x", ManifestStatus.MISSING, null, 4L));
    }

    // --- the head of the queue ---------------------------------------------------------------------

    @Test
    @DisplayName("The feed ends before the first batch with a table still pending, building or failed")
    void anUnfinishedTableHoldsTheFeed() {
        UUID first = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("a", 100L), 4L);
        UUID second = batch(2, "DELTA", "COMPLETED", 101, 110, Map.of("a", 5L, "b", 5L), 4L);
        UUID third = batch(3, "DELTA", "COMPLETED", 111, 120, Map.of("a", 10L), 4L);
        artifact(first, "a", "READY", 100L, T0.plusHours(2));
        artifact(second, "a", "READY", 5L, T0.plusHours(3));
        artifact(third, "a", "READY", 10L, T0.plusHours(4));

        for (String unfinished : List.of("PENDING", "BUILDING", "FAILED")) {
            jdbc.update("DELETE FROM batch_parquet_artifacts WHERE batch_id = ? AND table_name = 'b'", second);
            artifact(second, "b", unfinished, null, null);

            assertThat(listedIds()).as("second batch's table %s", unfinished).containsExactly(first);
        }

        jdbc.update("DELETE FROM batch_parquet_artifacts WHERE batch_id = ? AND table_name = 'b'", second);
        artifact(second, "b", "READY", 5L, T0.plusHours(5));

        assertThat(listedIds()).containsExactly(first, second, third);
    }

    @Test
    @DisplayName("An unsettled batch at or below the watermark does not hold the feed of a consumer past it")
    void anOldUnsettledBatchBelowTheWatermarkDoesNotHold() {
        UUID old = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("a", 100L), 4L);
        UUID next = batch(2, "DELTA", "COMPLETED", 101, 110, Map.of("a", 10L), 4L);
        artifact(old, "a", "FAILED", null, null);
        artifact(next, "a", "READY", 10L, T0.plusHours(3));

        assertThat(ids(service.changes(account, site, 4L, 100L, null, 50)))
                .as("a consumer that loaded /snapshot at 100 already holds the first batch").containsExactly(next);
        assertThat(listedIds())
                .as("one reading from the epoch's start still waits for it (#378)").isEmpty();
    }

    @Test
    @DisplayName("A batch before the cursor does not hold the next page — it was settled when its page was served")
    void aBatchBeforeTheCursorDoesNotHoldTheNextPage() {
        UUID a = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 10, Map.of("t", 10L), 4L);
        UUID b = batch(2, "DELTA", "COMPLETED", 11, 20, Map.of("t", 10L), 4L);
        artifact(a, "t", "ABANDONED", null, T0.plusHours(3));
        artifact(b, "t", "READY", 10L, T0.plusHours(4));
        ChangesPage first = service.changes(account, site, null, 0L, null, 1);
        assertThat(ids(first)).containsExactly(a);
        // An operator requeues the abandoned artifact (039) after the consumer read the page.
        jdbc.update("UPDATE batch_parquet_artifacts SET status = 'PENDING' WHERE batch_id = ?", a);

        assertThat(ids(service.changes(account, site, null, 0L, first.nextCursor(), 1))).containsExactly(b);
    }

    @Test
    @DisplayName("A completed batch still owed its work rows holds the feed instead of reading all missing (#380)")
    void aBatchAwaitingItsEnqueueHoldsTheFeed() {
        UUID first = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("a", 100L), 4L);
        UUID owed = batch(2, "DELTA", "COMPLETED", 101, 110, Map.of("a", 10L), 4L);
        UUID third = batch(3, "DELTA", "COMPLETED", 111, 120, Map.of("a", 10L), 4L);
        artifact(first, "a", "READY", 100L, T0.plusHours(2));
        artifact(third, "a", "READY", 10L, T0.plusHours(4));
        segment(owed, 101, 110);

        assertThat(listedIds()).containsExactly(first);

        artifact(owed, "a", "READY", 10L, T0.plusHours(5));

        assertThat(listedIds()).containsExactly(first, owed, third);
    }

    @Test
    @DisplayName("A batch never queued — older than the horizon, or a session the server ended — is listed missing")
    void aBatchNobodyWillBuildIsListedMissing() {
        UUID ancient = batchAt(T0.minusHours(400), "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), 4L);
        segment(ancient, 1, 10);
        UUID timedOut = batch(1, "CONTINUOUS", "NOT_COMPLETED", 11, 20, Map.of("a", 10L), 4L);
        segment(timedOut, 11, 20);

        ChangesPage page = service.changes(account, site, null, 0L, null, 50);

        assertThat(page.batches()).extracting(ChangesBatch::batchId).containsExactly(ancient, timedOut);
        assertThat(page.batches()).allSatisfy(b -> assertThat(b.tables())
                .containsExactly(new ChangesTable("a", ManifestStatus.MISSING, null, 10L)));
    }

    @Test
    @DisplayName("A running session and an empty one are not listed")
    void runningAndEmptySessionsAreNotListed() {
        UUID done = batch(1, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), 4L);
        artifact(done, "a", "READY", 10L, T0.plusHours(2));
        batch(2, "DELTA", "COMPLETED", null, null, Map.of(), 4L);
        batch(3, "DELTA", "IN_PROGRESS", 11, 20, Map.of("a", 10L), 4L);

        assertThat(listedIds()).containsExactly(done);
    }

    // --- the epoch ---------------------------------------------------------------------------------

    @Test
    @DisplayName("Only the current epoch is listed: stamped older batches and unstamped ones before the last snapshot are not")
    void onlyTheCurrentEpoch() {
        UUID stampedOld = batch(1, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), 3L);
        UUID unstampedOld = batch(2, "DELTA", "COMPLETED", 11, 20, Map.of("a", 10L), null);
        UUID snapshot = batch(3, "FULL_SNAPSHOT", "COMPLETED", 21, 40, Map.of("a", 20L), 4L);
        UUID unstampedNew = batch(4, "DELTA", "COMPLETED", 41, 50, Map.of("a", 10L), null);
        for (UUID b : List.of(stampedOld, unstampedOld, snapshot, unstampedNew)) {
            artifact(b, "a", "READY", 10L, T0.plusHours(5));
        }

        assertThat(listedIds()).containsExactly(snapshot, unstampedNew);
    }

    @Test
    @DisplayName("Unstamped history: the latest completed snapshot opens the epoch and is itself listed; a failed one does not")
    void anUnstampedSnapshotOpensTheEpoch() {
        UUID before = batch(1, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), null);
        UUID snapshot = batch(2, "FULL_SNAPSHOT", "COMPLETED", 11, 40, Map.of("a", 30L), null);
        UUID after = batch(3, "DELTA", "COMPLETED", 41, 50, Map.of("a", 10L), null);
        // Never committed, so it reset nothing: it does not move the boundary.
        batch(4, "FULL_SNAPSHOT", "FAILED", null, null, Map.of(), null);
        UUID afterTheFailure = batch(5, "DELTA", "COMPLETED", 51, 60, Map.of("a", 10L), null);
        for (UUID b : List.of(before, snapshot, after, afterTheFailure)) {
            artifact(b, "a", "READY", 10L, T0.plusHours(6));
        }

        assertThat(listedIds()).containsExactly(snapshot, after, afterTheFailure);
    }

    @Test
    @DisplayName("A site with no completed snapshot and only unstamped batches has every one of them in its epoch")
    void noSnapshotAtAllMeansEverythingIsCurrent() {
        UUID first = batch(1, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), null);
        UUID second = batch(2, "CONTINUOUS", "COMPLETED", 11, 20, Map.of("a", 10L), null);
        for (UUID b : List.of(first, second)) {
            artifact(b, "a", "READY", 10L, T0.plusHours(4));
        }

        assertThat(listedIds()).containsExactly(first, second);
    }

    @Test
    @DisplayName("A batch held inside the window under a cursor ends the page there, with no next cursor")
    void aHeldBatchUnderACursorEndsThePage() {
        UUID a = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 10, Map.of("t", 10L), 4L);
        UUID b = batch(2, "DELTA", "COMPLETED", 11, 20, Map.of("t", 10L), 4L);
        UUID c = batch(3, "DELTA", "COMPLETED", 21, 30, Map.of("t", 10L), 4L);
        UUID d = batch(4, "DELTA", "COMPLETED", 31, 40, Map.of("t", 10L), 4L);
        artifact(a, "t", "READY", 10L, T0.plusHours(5));
        artifact(b, "t", "READY", 10L, T0.plusHours(5));
        artifact(c, "t", "PENDING", null, null);
        artifact(d, "t", "READY", 10L, T0.plusHours(5));
        String cursor = new BitBiChangesCursor(site, T0.plusHours(1), a).encode();

        ChangesPage page = service.changes(account, site, null, 0L, cursor, 2);

        assertThat(ids(page)).containsExactly(b);
        assertThat(page.nextCursor()).as("the feed ends at the held batch for now").isNull();
    }

    @Test
    @DisplayName("afterSeq filters within the consumer's epoch, and a batch straddling it is listed whole")
    void afterSeqWithinTheEpoch() {
        UUID snapshot = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("a", 100L), 4L);
        UUID continuous = batch(2, "CONTINUOUS", "COMPLETED", 101, 200, Map.of("a", 100L), 4L);
        UUID delta = batch(3, "DELTA", "COMPLETED", 201, 210, Map.of("a", 10L), 4L);
        for (UUID b : List.of(snapshot, continuous, delta)) {
            artifact(b, "a", "READY", 10L, T0.plusHours(5));
        }

        assertThat(ids(service.changes(account, site, 4L, 150L, null, 50)))
                .as("the checkpoint fell inside the CONTINUOUS batch").containsExactly(continuous, delta);
        assertThat(ids(service.changes(account, site, 4L, 200L, null, 50))).containsExactly(delta);
        assertThat(ids(service.changes(account, site, 4L, 210L, null, 50))).isEmpty();
    }

    @Test
    @DisplayName("A watermark of a discarded epoch is answered from the new epoch's full_snapshot, even at a higher seq")
    void aStaleEpochStartsAgainAtTheSnapshot() {
        // After a wipe seq restarts at 1, so the consumer's old seq is far above the new snapshot.
        UUID snapshot = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 100, Map.of("a", 100L), 4L);
        UUID delta = batch(2, "DELTA", "COMPLETED", 101, 110, Map.of("a", 10L), 4L);
        artifact(snapshot, "a", "READY", 100L, T0.plusHours(2));
        artifact(delta, "a", "READY", 10L, T0.plusHours(3));

        ChangesPage page = service.changes(account, site, 3L, 5_000_000L, null, 50);

        assertThat(ids(page)).containsExactly(snapshot, delta);
        assertThat(page.batches().getFirst().sessionMode()).isEqualTo("full_snapshot");
        assertThat(page.batches()).allSatisfy(b -> assertThat(b.epoch()).isEqualTo(4L));
    }

    @Test
    @DisplayName("afterSeq without the epoch it belongs to is refused")
    void afterSeqNeedsItsEpoch() {
        assertThatThrownBy(() -> service.changes(account, site, null, 10L, null, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("epoch");
    }

    // --- cursor and ownership ----------------------------------------------------------------------

    @Test
    @DisplayName("Pages are walked with nextCursor; the last page has none")
    void pagesAreWalkedWithTheCursor() {
        UUID a = batch(1, "FULL_SNAPSHOT", "COMPLETED", 1, 10, Map.of("t", 10L), 4L);
        UUID b = batch(2, "DELTA", "COMPLETED", 11, 20, Map.of("t", 10L), 4L);
        UUID c = batch(3, "DELTA", "COMPLETED", 21, 30, Map.of("t", 10L), 4L);
        for (UUID batch : List.of(a, b, c)) {
            artifact(batch, "t", "READY", 10L, T0.plusHours(5));
        }

        ChangesPage first = service.changes(account, site, null, 0L, null, 2);
        ChangesPage second = service.changes(account, site, null, 0L, first.nextCursor(), 2);

        assertThat(ids(first)).containsExactly(a, b);
        assertThat(first.nextCursor()).isEqualTo(new BitBiChangesCursor(site, T0.plusHours(2), b).encode());
        assertThat(ids(second)).containsExactly(c);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    @DisplayName("A cursor of another site, or not one this server issued, is refused; another account's site is absent")
    void cursorAndOwnership() {
        UUID otherSite = UUID.randomUUID();
        insertSite(otherSite, account);
        String foreignCursor = new BitBiChangesCursor(otherSite, T0, UUID.randomUUID()).encode();
        UUID otherAccount = UUID.randomUUID();

        assertThatThrownBy(() -> service.changes(account, site, null, 0L, foreignCursor, 50))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid cursor");
        assertThatThrownBy(() -> service.changes(account, site, null, 0L, "garbage", 50))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid cursor");
        assertThatThrownBy(() -> service.changes(otherAccount, site, null, 0L, null, 50))
                .isInstanceOf(SiteService.SiteNotFoundException.class);
    }

    // --- /files ------------------------------------------------------------------------------------

    @Test
    @DisplayName("A batch fileId resolves while its artifact is ready and its batch is in the current epoch")
    void batchFileResolution() {
        UUID current = batch(1, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L, "b", 1L), 4L);
        UUID old = batch(0, "DELTA", "COMPLETED", 1, 10, Map.of("a", 10L), 3L);
        artifact(current, "a", "READY", 10L, T0.plusHours(2));
        artifact(current, "b", "ABANDONED", null, T0.plusHours(2));
        artifact(old, "a", "READY", 10L, T0.plusHours(2));

        assertThat(service.readyBatchFileKey(account, new BitBiFileId.BatchFile(site, current, "a")))
                .contains(keyOf(current, "a"));
        assertThat(service.readyBatchFileKey(account, new BitBiFileId.BatchFile(site, current, "b"))).isEmpty();
        assertThat(service.readyBatchFileKey(account, new BitBiFileId.BatchFile(site, old, "a")))
                .as("a discarded epoch").isEmpty();
        assertThat(service.readyBatchFileKey(UUID.randomUUID(), new BitBiFileId.BatchFile(site, current, "a")))
                .as("another account").isEmpty();
        assertThat(service.readyBatchFileKey(account, new BitBiFileId.BatchFile(UUID.randomUUID(), current, "a")))
                .as("another site named in the id").isEmpty();
    }

    // --- seeding -----------------------------------------------------------------------------------

    private List<UUID> listedIds() {
        return ids(service.changes(account, site, null, 0L, null, 50));
    }

    private static List<UUID> ids(ChangesPage page) {
        return page.batches().stream().map(ChangesBatch::batchId).toList();
    }

    private void insertSite(UUID id, UUID owner) {
        jdbc.update("""
                INSERT INTO sites (id, account_id, domain, display_name, is_active, created_at, updated_at,
                                   site_name, client_api_version)
                VALUES (?, ?, ?, 'issue 388', true, ?, ?, ?, 'V2')
                """, id, owner, id + ".example.com", T0, T0, id + ".example.com");
    }

    private UUID batch(int hour, String mode, String status, Integer firstSeq, Integer lastSeq,
                       Map<String, Long> records, Long epoch) {
        return batchAt(T0.plusHours(hour), mode, status, firstSeq, lastSeq, records, epoch);
    }

    private UUID batchAt(LocalDateTime startedAt, String mode, String status, Integer firstSeq, Integer lastSeq,
                         Map<String, Long> records, Long epoch) {
        UUID id = UUID.randomUUID();
        StringBuilder stats = new StringBuilder("{");
        new LinkedHashMap<>(records).forEach((table, count) -> {
            if (stats.length() > 1) {
                stats.append(',');
            }
            stats.append("\"%s\":{\"inserts\":%d,\"updates\":0,\"deletes\":0}".formatted(table, count));
        });
        stats.append('}');
        jdbc.update("""
                INSERT INTO batches (id, account_id, site_id, status, s3_path, uploaded_files_count, total_size,
                                     has_errors, started_at, created_at, completed_at, session_mode,
                                     total_records, table_count, table_stats, first_seq, last_seq, baseline_epoch)
                VALUES (?, ?, ?, ?, 'issue-388/', 0, 0, false, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """, id, account, site, status, startedAt, startedAt,
                "IN_PROGRESS".equals(status) ? null : startedAt.plusMinutes(5), mode,
                records.values().stream().mapToLong(Long::longValue).sum(), records.size(), stats.toString(),
                firstSeq, lastSeq, epoch);
        return id;
    }

    private void artifact(UUID batch, String table, String status, Long rowCount, LocalDateTime at) {
        boolean ready = "READY".equals(status);
        LocalDateTime updatedAt = at == null ? T0 : at;
        jdbc.update("""
                INSERT INTO batch_parquet_artifacts
                    (id, site_id, batch_id, table_name, status, s3_key, row_count, file_size, checksum,
                     attempt_count, created_at, updated_at, ready_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0)
                """, UUID.randomUUID(), site, batch, table, status, ready ? keyOf(batch, table) : null,
                rowCount, ready ? 1L : null, ready ? "sum" : null, updatedAt, updatedAt, ready ? at : null);
    }

    private String keyOf(UUID batch, String table) {
        return "egress/%s/batches/%s/attempts/%s/%s.parquet".formatted(site, batch, batch, table);
    }

    private void segment(UUID batch, long firstSeq, long lastSeq) {
        // Both queue markers stamped, so the site-blind egress and delta-SQL queues never claim it.
        jdbc.update("""
                INSERT INTO changelog_segments (id, site_id, batch_id, first_seq, last_seq, record_count,
                    content_hash, s3_key, mode, plugin_sql_at, egress_at, provisional)
                VALUES (?, ?, ?, ?, ?, 1, 'hash', ?, 'DELTA', ?, ?, false)
                """, UUID.randomUUID(), site, batch, firstSeq, lastSeq,
                "issue-388/" + batch + "/" + firstSeq + ".pb.gz", T0, T0);
    }
}
