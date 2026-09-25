package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #345 — the per-site checkpoint claim, against the real statements.
 *
 * <p>The claim is SQL inside {@code @Query}, a contract neither the compiler nor CI checks, so
 * every property the coordination rests on is driven here through the real repository: a free site
 * is taken by exactly one token, a foreign token can neither renew nor release it, an expired lease
 * is taken over (the pod that died mid-build — HPA scale-down and preemption do this nightly), and a
 * whole-entity save of {@code SiteSyncState} cannot put a stale claim back (#245's clobber).</p>
 */
@DisplayName("Checkpoint site claim — repository statements (#345)")
class CheckpointSiteClaimIntegrationTest extends BaseIntegrationTest {

    /** store-03, which no other checkpoint test builds. */
    private static final UUID SITE = UUID.fromString("0199bab0-ca3b-e41c-5521-2f4b33fda8b6");

    private static final long LEASE_SECONDS = 600L;

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private DeltaSyncStateService syncStateService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void freeTheSite() {
        // A claim left behind would make every other class's tick skip this site for a lease.
        jdbc.update("UPDATE site_sync_state SET checkpoint_claim_token = NULL, "
                + "checkpoint_claim_expires_at = NULL WHERE site_id = ?", SITE);
    }

    @Test
    @DisplayName("a free site is taken by the first token and refused to a second")
    void shouldGiveAFreeSiteToExactlyOneToken() {
        syncStateService.advanceWatermark(SITE, 10L);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(syncStateRepository.claimCheckpointSite(SITE, first, LEASE_SECONDS)).isEqualTo(1);
        assertThat(syncStateRepository.claimCheckpointSite(SITE, second, LEASE_SECONDS)).isZero();

        assertThat(claimToken()).isEqualTo(first);
        assertThat(expiresInSeconds()).isBetween(LEASE_SECONDS - 60, LEASE_SECONDS + 60);
    }

    @Test
    @DisplayName("the holder's token extends the lease, a stranger's does not")
    void shouldLetTheHolderRenewButNotAStranger() {
        syncStateService.advanceWatermark(SITE, 10L);
        UUID holder = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        syncStateRepository.claimCheckpointSite(SITE, holder, 1L);

        assertThat(syncStateRepository.renewCheckpointSiteClaim(SITE, stranger, LEASE_SECONDS)).isZero();
        assertThat(expiresInSeconds()).isLessThan(60L);

        assertThat(syncStateRepository.renewCheckpointSiteClaim(SITE, holder, LEASE_SECONDS)).isEqualTo(1);
        assertThat(expiresInSeconds()).isGreaterThan(LEASE_SECONDS - 60);
        assertThat(claimToken()).isEqualTo(holder);
    }

    @Test
    @DisplayName("a foreign token cannot release a claim, and the holder's release frees the site")
    void shouldReleaseOnlyForTheHolder() {
        syncStateService.advanceWatermark(SITE, 10L);
        UUID holder = UUID.randomUUID();
        syncStateRepository.claimCheckpointSite(SITE, holder, LEASE_SECONDS);

        assertThat(syncStateRepository.releaseCheckpointSiteClaim(SITE, UUID.randomUUID())).isZero();
        assertThat(claimToken()).isEqualTo(holder);

        assertThat(syncStateRepository.releaseCheckpointSiteClaim(SITE, holder)).isEqualTo(1);
        assertThat(claimToken()).isNull();
        assertThat(syncStateRepository.claimCheckpointSite(SITE, UUID.randomUUID(), LEASE_SECONDS))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an expired lease is taken over, and the pod that lost it can neither renew nor release")
    void shouldTakeOverAnExpiredLease() {
        // The pod died mid-build (#345: HPA scale-down at 02:19–02:26, preemption): its claim is
        // still on the row and nobody will release it. The next replica takes it once the lease
        // lapses — and if the old pod was only paused, its late renewal and release must not
        // touch the new owner's claim.
        syncStateService.advanceWatermark(SITE, 10L);
        UUID dead = UUID.randomUUID();
        syncStateRepository.claimCheckpointSite(SITE, dead, LEASE_SECONDS);
        jdbc.update("UPDATE site_sync_state SET checkpoint_claim_expires_at = "
                + "CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp) - INTERVAL '1 second' "
                + "WHERE site_id = ?", SITE);
        UUID next = UUID.randomUUID();

        assertThat(syncStateRepository.claimCheckpointSite(SITE, next, LEASE_SECONDS)).isEqualTo(1);
        assertThat(claimToken()).isEqualTo(next);

        assertThat(syncStateRepository.renewCheckpointSiteClaim(SITE, dead, LEASE_SECONDS)).isZero();
        assertThat(syncStateRepository.releaseCheckpointSiteClaim(SITE, dead)).isZero();
        assertThat(claimToken()).isEqualTo(next);
    }

    @Test
    @DisplayName("a site with no sync-state row yet is claimable, and the claim creates the row")
    void shouldClaimASiteThatHasNoRowYet() {
        jdbc.update("DELETE FROM site_sync_state WHERE site_id = ?", SITE);
        UUID token = UUID.randomUUID();

        assertThat(syncStateRepository.claimCheckpointSite(SITE, token, LEASE_SECONDS)).isEqualTo(1);
        assertThat(claimToken()).isEqualTo(token);
        assertThat(syncStateService.getSyncState(SITE).lastCheckpointSeq()).isZero();
        assertThat(syncStateRepository.claimCheckpointSite(SITE, UUID.randomUUID(), LEASE_SECONDS))
                .isZero();
    }

    @Test
    @DisplayName("a whole-entity save of the sync state does not touch the claim")
    void shouldSurviveAWholeEntitySave() {
        // Every other writer of this row saves the whole entity (the watermark, the pointer, the
        // abort, the rebuild verdict). Were the claim mapped on it, a save built from a snapshot
        // read before the claim would write it back to NULL — #245's clobber, now on the one column
        // that keeps two pods apart.
        syncStateService.advanceWatermark(SITE, 10L);
        UUID holder = UUID.randomUUID();
        syncStateRepository.claimCheckpointSite(SITE, holder, LEASE_SECONDS);

        syncStateService.advanceWatermark(SITE, 20L);
        syncStateService.recordCheckpoint(SITE, 20L);

        assertThat(claimToken()).isEqualTo(holder);
        assertThat(expiresInSeconds()).isGreaterThan(LEASE_SECONDS - 60);
    }

    @Test
    @DisplayName("two replicas racing for one site on their own connections: exactly one gets it")
    void shouldGiveTheSiteToOneOfTwoRacingClaims() throws Exception {
        syncStateService.advanceWatermark(SITE, 10L);
        int contenders = 2;
        CyclicBarrier start = new CyclicBarrier(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                UUID token = UUID.randomUUID();
                Callable<Integer> claim = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return syncStateRepository.claimCheckpointSite(SITE, token, LEASE_SECONDS);
                };
                results.add(pool.submit(claim));
            }
            int won = 0;
            for (Future<Integer> result : results) {
                won += result.get(30, TimeUnit.SECONDS);
            }
            assertThat(won).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID claimToken() {
        return jdbc.queryForObject(
                "SELECT checkpoint_claim_token FROM site_sync_state WHERE site_id = ?", UUID.class, SITE);
    }

    /** Seconds from the database's own UTC clock to the lease's end, as the statements see it. */
    private long expiresInSeconds() {
        Map<String, Object> row = jdbc.queryForMap("SELECT CAST(EXTRACT(EPOCH FROM ("
                + "checkpoint_claim_expires_at - CAST(current_timestamp AT TIME ZONE 'UTC' AS timestamp)"
                + ")) AS bigint) AS remaining FROM site_sync_state WHERE site_id = ?", SITE);
        return ((Number) row.get("remaining")).longValue();
    }
}
