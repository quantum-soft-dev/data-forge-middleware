package com.bitbi.dfm.integration;

import com.bitbi.dfm.delta.application.ChangelogSegmentService;
import com.bitbi.dfm.delta.application.CheckpointService;
import com.bitbi.dfm.delta.application.DeltaMetrics;
import com.bitbi.dfm.delta.application.DeltaS3OrphanSweeper;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteSyncStateRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.storage.S3ChildPrefixListing;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Issue #158 — the orphan sweep against a real bucket.
 *
 * <p>The unit tests mock both storages, so three things are unproven there and are exactly what
 * breaks first: that a delimiter listing of {@code delta/} and {@code checkpoints/} really answers
 * one site prefix per site, that the key shapes the sweeper matches are the keys the writers
 * actually produce, and that the batched delete removes what it was handed.</p>
 *
 * <p>Age is the one guard a test cannot exercise by waiting, so the sweep is driven with a
 * {@code now} two days ahead instead of backdating an S3 {@code LastModified} the bucket owns.</p>
 *
 * <p><b>The sweep is narrowed to this class's two sites, on purpose.</b> A cutoff in the future
 * makes every unreferenced object in the shared bucket a candidate, including ones earlier classes
 * left, and a test that deletes across the suite is the hazard #168 was filed for. So the real
 * delimiter listing is asserted to contain both site prefixes — that is the part a mock cannot
 * prove — and the sweep is then driven through a spy that answers only those two. Everything below
 * it stays real: the per-site listing, the rows, and the batched delete.</p>
 */
@DisplayName("Delta S3 orphan sweep against LocalStack (#158)")
class DeltaS3OrphanSweepIntegrationTest extends BaseIntegrationTest {

    private static final UUID SITE = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654"); // store-01
    private static final UUID BATCH = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    @Autowired
    private S3FileStorageService objectDeleter;

    @Autowired
    private com.bitbi.dfm.site.domain.SiteRepository siteRepository;

    @Autowired
    private DeltaMetrics metrics;

    @Autowired
    private ChangelogSegmentService changelogSegmentService;

    @Autowired
    private CheckpointService checkpointService;

    @Autowired
    private ChangelogSegmentRepository segmentRepository;

    @Autowired
    private CheckpointRepository checkpointRepository;

    @Autowired
    private SiteSyncStateRepository syncStateRepository;

    @Autowired
    private S3ChangelogSegmentStorage segmentStorage;

    @Autowired
    private S3CheckpointStorage checkpointStorage;

    @Autowired
    private S3Client s3Client;

    @org.springframework.beans.factory.annotation.Value("${s3.bucket.name}")
    private String bucketName;

    /**
     * The bucket is shared by the whole suite and checkpoint keys carry a sequence number rather
     * than a run identity, so an earlier class's {@code _frame/seq=2/frame.pb.gz} for this site
     * would make the "the build wrote the frame" precondition true without this build writing
     * anything (raised in review).
     */
    @BeforeEach
    void purgeLeftovers() {
        purgeCheckpointPrefix(SITE);
        purgeSegmentPrefix(SITE);
    }

    @Test
    @DisplayName("what the rows still name survives; what nothing names is reclaimed")
    void reclaimsOnlyTheObjectsNoRowNames() {
        // A live segment and a live checkpoint for a seeded site: rows plus the objects they name.
        changelogSegmentService.persist(SITE, BATCH, "FULL_SNAPSHOT", 1L, List.of(
                rec("customers", Op.INSERT, 1L, key("id", 1L), data("id", 1L, "name", "Ann")),
                rec("customers", Op.INSERT, 2L, key("id", 2L), data("id", 2L, "name", "Bob"))));
        checkpointService.buildCheckpoint(SITE);

        ChangelogSegment segment = segmentRepository.findBySiteIdAndFirstSeq(SITE, 1L).orElseThrow();
        String liveSegment = segment.getS3Key();
        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(SITE, "customers")
                .orElseThrow();
        long checkpointSeq = syncStateRepository.findBySiteId(SITE).orElseThrow()
                .getLastCheckpointSeq();
        String liveFrame = S3CheckpointStorage.frameKey(SITE, checkpointSeq);

        // The seeded site declares no schema, so the build materializes no Parquet (#113) and the
        // row's key is null. The snapshot half of the cross-check is what this class is about, so
        // the row is given one: an object at the key a build would have written, named by the row.
        String liveSnapshot = S3CheckpointStorage.checkpointPrefix(SITE)
                + "customers/seq=" + checkpointSeq + "/snapshot.parquet";
        put(liveSnapshot);
        checkpoint.attachParquet(liveSnapshot);
        checkpointRepository.save(checkpoint);

        assertTrue(segmentStorage.exists(liveSegment), "the live segment object exists to begin with");
        assertTrue(checkpointStorage.exists(liveFrame),
                "the build wrote the frame at the sequence the pointer now names");

        // Objects with no row: what a failed commit, a superseded build and a hard-deleted site
        // leave behind. The last one is why the sites come from the bucket and not the database.
        String orphanSegment = S3ChangelogSegmentStorage.segmentPrefix(SITE)
                + UUID.randomUUID() + ".pb.gz";
        // Below the pointer: a build can only ever upload at a sequence above it, so this key can
        // never be adopted again. One above the pointer is the opposite case, asserted as kept.
        String orphanFrame = S3CheckpointStorage.frameKey(SITE, checkpointSeq - 1);
        String frameAheadOfThePointer = S3CheckpointStorage.frameKey(SITE, checkpointSeq + 5);
        String orphanSnapshot = S3CheckpointStorage.checkpointPrefix(SITE)
                + "customers/seq=0/snapshot.parquet";
        String unknownShape = S3CheckpointStorage.checkpointPrefix(SITE) + "manifest.json";
        UUID deletedSite = UUID.randomUUID();
        String deletedSiteSegment = S3ChangelogSegmentStorage.segmentPrefix(deletedSite)
                + UUID.randomUUID() + ".pb.gz";
        String deletedSiteFrame = S3CheckpointStorage.frameKey(deletedSite, 1L);
        for (String key : List.of(orphanSegment, orphanFrame, frameAheadOfThePointer, orphanSnapshot,
                unknownShape, deletedSiteSegment, deletedSiteFrame)) {
            put(key);
        }

        // The delimiter walk against the real bucket: both sites must be in it, including the one
        // with no rows at all, which is the whole reason the site list comes from S3.
        assertTrue(segmentStorage.listSitePrefixes().prefixes()
                        .containsAll(List.of("delta/" + SITE + "/", "delta/" + deletedSite + "/")),
                "the delimiter listing of delta/ answers one prefix per site with objects");
        assertTrue(checkpointStorage.listSitePrefixes().prefixes()
                        .containsAll(List.of(S3CheckpointStorage.checkpointPrefix(SITE),
                                S3CheckpointStorage.checkpointPrefix(deletedSite))),
                "the delimiter listing of checkpoints/ answers one prefix per site with objects");

        try {
            scopedTo(SITE, deletedSite).sweep(Instant.now().plus(2, ChronoUnit.DAYS));

            assertTrue(segmentStorage.exists(liveSegment),
                    "a segment object its changelog row still names must survive");
            assertTrue(checkpointStorage.exists(liveFrame),
                    "the frame at last_checkpoint_seq is named by no row and must survive anyway — "
                            + "it is the seed of the next incremental build");
            assertTrue(checkpointStorage.exists(liveSnapshot),
                    "the snapshot the checkpoint row names must survive");
            assertTrue(checkpointStorage.exists(unknownShape),
                    "a key of a shape this application does not write is never a candidate");
            assertTrue(checkpointStorage.exists(frameAheadOfThePointer),
                    "a seq-addressed key the pointer has not passed can still be uploaded and "
                            + "adopted between the listing and the delete, so it survives");

            assertFalse(segmentStorage.exists(orphanSegment), "an unreferenced segment is reclaimed");
            assertFalse(checkpointStorage.exists(orphanFrame),
                    "a frame below the pointer can never be adopted again and is reclaimed");
            assertFalse(checkpointStorage.exists(orphanSnapshot),
                    "a superseded snapshot is reclaimed");
            assertFalse(segmentStorage.exists(deletedSiteSegment),
                    "a hard-deleted site's segment object is reclaimed — the sites come from the "
                            + "bucket, so a site with no rows at all is still visited");
            assertFalse(checkpointStorage.exists(deletedSiteFrame),
                    "a hard-deleted site's frame is reclaimed");
        } finally {
            // Everything this class created and the sweep spared, so the next class starts clean.
            purgeCheckpointPrefix(SITE);
            purgeSegmentPrefix(SITE);
            purgeCheckpointPrefix(deletedSite);
            purgeSegmentPrefix(deletedSite);
        }
    }

    /**
     * The production sweeper with one thing replaced: the list of sites it visits. Every other
     * collaborator is the real bean, so the per-site listing, the row cross-check and the delete all
     * run for real — this only keeps a cutoff two days in the future from reaching objects other
     * classes left in the shared bucket.
     */
    private DeltaS3OrphanSweeper scopedTo(UUID... sites) {
        S3ChangelogSegmentStorage segments = spy(segmentStorage);
        doReturn(S3ChildPrefixListing.complete(Stream.of(sites)
                .map(S3ChangelogSegmentStorage::segmentPrefix)
                // The scope discovers sites from delta/{siteId}/, one level above the segments.
                .map(prefix -> prefix.substring(0, prefix.length() - "segments/".length()))
                .toList()))
                .when(segments).listSitePrefixes();

        S3CheckpointStorage checkpoints = spy(checkpointStorage);
        doReturn(S3ChildPrefixListing.complete(Stream.of(sites)
                .map(S3CheckpointStorage::checkpointPrefix).toList()))
                .when(checkpoints).listSitePrefixes();

        // dry-run=false: the shipped default reports instead of deleting, and what this class is
        // about is the delete. reclaim-unknown-sites=true: `deletedSite` has no `sites` row, which
        // is the hard-deleted case, and reaching it is the point of that acknowledgement.
        return new DeltaS3OrphanSweeper(segments, checkpoints, objectDeleter, segmentRepository,
                checkpointRepository, syncStateRepository, siteRepository, metrics, true, false,
                true, 86_400L);
    }

    private void put(String key) {
        s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                RequestBody.fromBytes("orphan".getBytes(StandardCharsets.UTF_8)));
    }

    private static ChangeRecord rec(String table, Op op, long seq, Map<String, Value> key,
                                    Map<String, Value> data) {
        return ChangeRecord.newBuilder().setTable(table).setOp(op).setSeq(seq)
                .putAllKey(key).putAllData(data).build();
    }

    private static Map<String, Value> key(String column, long value) {
        return Map.of(column, Value.newBuilder().setIntValue(value).build());
    }

    private static Map<String, Value> data(String idColumn, long id, String nameColumn, String name) {
        Map<String, Value> map = new LinkedHashMap<>();
        map.put(idColumn, Value.newBuilder().setIntValue(id).build());
        map.put(nameColumn, Value.newBuilder().setStringValue(name).build());
        return map;
    }
}
