package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.CheckpointRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * {@code delta.checkpoint.tables.given-up} — checkpoint rows the nightly pass has stopped retrying
 * (issue #149).
 *
 * <p>The counterpart of the attempt cap, and the reason the cap is safe to have. Before it, a table
 * that could never be materialized announced itself every night: a wasted frame download, a
 * whole-site fold and a {@code delta.checkpoint.tables.unmaterialized} increment. Retiring the row
 * removes that noise — and would remove the signal with it, leaving a table permanently missing
 * from Bit BI and Parquet Export with nothing left to say so. This gauge is what stays behind: a
 * standing level, not an event, so it reads as "there are N tables nobody is going to fix" rather
 * than as a rate that decays to zero and looks resolved.</p>
 *
 * <p>A non-zero value is not automatically an incident — the usual cause is a site whose client
 * never sent {@code SubmitSchema} for a table it streams. It becomes one when it climbs. The exits
 * are a schema submission, a forced rebuild, a re-baseline or a wipe; each resets or removes the
 * rows and the gauge follows.</p>
 *
 * <p>Snapshot-cached like {@code delta.batch-parquet.queue}: a scrape must not turn into a table
 * scan per meter, and the number changes at most once a night.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Component
public class CheckpointGivenUpMetrics implements MeterBinder {

    private static final String METER_NAME = "delta.checkpoint.tables.given-up";
    private static final String APP_TAG_KEY = "application";
    private static final String APP_TAG_VALUE = "data-forge-middleware";
    private static final Duration SNAPSHOT_TTL = Duration.ofSeconds(30);

    private final CheckpointRepository repository;
    private final CheckpointRetryProperties retryProperties;
    private final LongSupplier nanoTime;
    private final long snapshotTtlNanos;
    private volatile Snapshot snapshot = Snapshot.uninitialized();

    @Autowired
    public CheckpointGivenUpMetrics(CheckpointRepository repository,
                                    CheckpointRetryProperties retryProperties) {
        this(repository, retryProperties, System::nanoTime, SNAPSHOT_TTL);
    }

    CheckpointGivenUpMetrics(CheckpointRepository repository,
                             CheckpointRetryProperties retryProperties,
                             LongSupplier nanoTime,
                             Duration snapshotTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.retryProperties = Objects.requireNonNull(retryProperties, "retryProperties");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.snapshotTtlNanos = Objects.requireNonNull(snapshotTtl, "snapshotTtl").toNanos();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(METER_NAME, this, CheckpointGivenUpMetrics::givenUpTables)
                .description("Checkpoint tables with no snapshot that the nightly pass no longer retries")
                .tag(APP_TAG_KEY, APP_TAG_VALUE)
                .register(Objects.requireNonNull(registry, "registry"));
    }

    private long givenUpTables() {
        long now = nanoTime.getAsLong();
        Snapshot current = snapshot;
        if (!isFresh(current, now)) {
            current = refreshSnapshot(now);
        }
        return current.givenUp();
    }

    private synchronized Snapshot refreshSnapshot(long now) {
        Snapshot current = snapshot;
        if (isFresh(current, now)) {
            return current;
        }
        long givenUp = repository.countGivenUpMaterializing(retryProperties.maxMaterializeAttempts());
        Snapshot refreshed = new Snapshot(givenUp, nanoTime.getAsLong(), true);
        snapshot = refreshed;
        return refreshed;
    }

    private boolean isFresh(Snapshot candidate, long now) {
        return candidate.initialized() && now - candidate.refreshedAtNanos() < snapshotTtlNanos;
    }

    private record Snapshot(long givenUp, long refreshedAtNanos, boolean initialized) {

        private static Snapshot uninitialized() {
            return new Snapshot(0, 0, false);
        }
    }
}
