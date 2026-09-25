package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #345 — one replica visits a site's checkpoint at a time.
 *
 * <p>The statements themselves are held against PostgreSQL by
 * {@code CheckpointSiteClaimIntegrationTest}; this class holds what is built on them: the work runs
 * only under a claim, the claim is released whichever way the work ends, the lease is renewed while
 * the work is alive, and the forced rebuild's wait ends on a shutdown rather than holding context
 * close.</p>
 */
@DisplayName("CheckpointSiteClaim (#345)")
class CheckpointSiteClaimTest {

    private static final UUID SITE = UUID.randomUUID();

    private final SiteSyncStateRepository repository = mock(SiteSyncStateRepository.class);
    private volatile boolean shuttingDown;
    private final ApplicationShutdownSignal shutdownSignal = new ApplicationShutdownSignal() {
        @Override
        public boolean isShuttingDown() {
            return shuttingDown;
        }
    };
    private CheckpointSiteClaim claim;

    @AfterEach
    void stopRenewals() {
        if (claim != null) {
            claim.shutdown();
        }
    }

    private CheckpointSiteClaim claim(long leaseSeconds, long waitSeconds, long pollMillis) {
        claim = new CheckpointSiteClaim(repository, shutdownSignal, leaseSeconds, waitSeconds, pollMillis);
        return claim;
    }

    @Test
    @DisplayName("a free site runs the work under a claim and releases it with the same token")
    void shouldRunTheWorkUnderTheClaimAndReleaseIt() {
        when(repository.claimCheckpointSite(eq(SITE), any(), eq(600L))).thenReturn(1);

        Optional<String> result = claim(600, 600, 10).runIfFree(SITE, () -> "built");

        assertThat(result).contains("built");
        ArgumentCaptor<UUID> claimed = ArgumentCaptor.forClass(UUID.class);
        verify(repository).claimCheckpointSite(eq(SITE), claimed.capture(), eq(600L));
        verify(repository).releaseCheckpointSiteClaim(SITE, claimed.getValue());
    }

    @Test
    @DisplayName("a site another replica holds is skipped: the work does not run and nothing is released")
    void shouldSkipASiteClaimedElsewhere() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(0);
        AtomicInteger runs = new AtomicInteger();

        Optional<Integer> result = claim(600, 600, 10).runIfFree(SITE, runs::incrementAndGet);

        assertThat(result).isEmpty();
        assertThat(runs).hasValue(0);
        verify(repository, never()).releaseCheckpointSiteClaim(any(), any());
    }

    @Test
    @DisplayName("the claim is released when the work throws, and the work's own exception is what escapes")
    void shouldReleaseWhenTheWorkThrows() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(1);
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> claim(600, 600, 10).runIfFree(SITE, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(repository).releaseCheckpointSiteClaim(eq(SITE), any());
    }

    @Test
    @DisplayName("a release that fails does not replace the work's exception — the lease lapses on its own")
    void shouldNotLetAFailedReleaseMaskTheWorksFailure() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(1);
        when(repository.releaseCheckpointSiteClaim(eq(SITE), any()))
                .thenThrow(new IllegalStateException("database gone"));
        IllegalStateException boom = new IllegalStateException("boom");

        assertThatThrownBy(() -> claim(600, 600, 10).runIfFree(SITE, () -> {
            throw boom;
        })).isSameAs(boom);
    }

    @Test
    @DisplayName("a release that fails after a successful visit is logged, not thrown")
    void shouldNotFailASuccessfulVisitOnAFailedRelease() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(1);
        when(repository.releaseCheckpointSiteClaim(eq(SITE), any()))
                .thenThrow(new IllegalStateException("database gone"));

        assertThat(claim(600, 600, 10).runIfFree(SITE, () -> "built")).contains("built");
    }

    @Test
    @DisplayName("the lease is renewed with the holder's token while the work is still running")
    void shouldRenewTheLeaseWhileTheWorkRuns() {
        // A first checkpoint of a 5-million-row site takes 10-30 minutes. A lease that is not
        // renewed would lapse mid-build and hand the site to the next replica — the double build
        // this claim exists to prevent, back again for exactly the sites that cost the most.
        when(repository.claimCheckpointSite(eq(SITE), any(), eq(1L))).thenReturn(1);
        when(repository.renewCheckpointSiteClaim(eq(SITE), any(), eq(1L))).thenReturn(1);

        claim(1, 600, 10).runIfFree(SITE, () -> {
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> verify(repository, atLeast(2))
                            .renewCheckpointSiteClaim(eq(SITE), any(), eq(1L)));
            return "renewed";
        });

        ArgumentCaptor<UUID> claimed = ArgumentCaptor.forClass(UUID.class);
        verify(repository).claimCheckpointSite(eq(SITE), claimed.capture(), eq(1L));
        verify(repository, atLeastOnce()).renewCheckpointSiteClaim(SITE, claimed.getValue(), 1L);
    }

    @Test
    @DisplayName("no renewal outlives the visit")
    void shouldStopRenewingOnceTheWorkEnds() throws InterruptedException {
        when(repository.claimCheckpointSite(eq(SITE), any(), eq(1L))).thenReturn(1);

        claim(1, 600, 10).runIfFree(SITE, () -> "done");
        Thread.sleep(1_000);

        verify(repository, never()).renewCheckpointSiteClaim(any(), any(), anyLong());
    }

    @Test
    @DisplayName("the forced rebuild waits for a claim held elsewhere and runs once it is free")
    void shouldWaitForTheClaimAndThenRun() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(0, 0, 1);

        String result = claim(600, 60, 10).runWhenFree(SITE, () -> "rebuilt");

        assertThat(result).isEqualTo("rebuilt");
        verify(repository, org.mockito.Mockito.times(3)).claimCheckpointSite(eq(SITE), any(), anyLong());
    }

    @Test
    @DisplayName("a wait spent without the claim coming free says so and runs nothing")
    void shouldGiveUpAfterTheWait() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenReturn(0);
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> claim(600, 1, 50).runWhenFree(SITE, runs::incrementAndGet))
                .isInstanceOfSatisfying(CheckpointSiteClaim.SiteClaimedElsewhereException.class, e -> {
                    assertThat(e.waitWasSpent()).isTrue();
                    assertThat(e.getMessage()).contains(SITE.toString())
                            .contains("delta.checkpoint.claim-wait-seconds");
                });
        assertThat(runs).hasValue(0);
    }

    @Test
    @DisplayName("a shutdown ends the wait early rather than holding context close")
    void shouldEndTheWaitOnShutdown() {
        when(repository.claimCheckpointSite(eq(SITE), any(), anyLong())).thenAnswer(invocation -> {
            shuttingDown = true;
            return 0;
        });

        long started = System.nanoTime();
        assertThatThrownBy(() -> claim(600, 600, 50).runWhenFree(SITE, () -> "rebuilt"))
                .isInstanceOfSatisfying(CheckpointSiteClaim.SiteClaimedElsewhereException.class,
                        e -> assertThat(e.waitWasSpent()).isFalse());
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a lease below one second is refused at startup, naming the key and the value")
    void shouldRefuseALeaseBelowOneSecond() {
        assertThatThrownBy(() -> new CheckpointSiteClaim(repository, shutdownSignal, 0, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delta.checkpoint.claim-lease-seconds")
                .hasMessageContaining("but was 0");
    }
}
