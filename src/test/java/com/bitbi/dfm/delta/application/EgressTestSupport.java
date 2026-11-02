package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.lifecycle.ApplicationShutdownSignal;
import com.bitbi.dfm.site.application.SiteSchemaService;
import io.micrometer.core.instrument.MeterRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Shared wiring for the egress unit tests (issue #404): the service streams a segment through
 * {@link ChangelogSegmentService#forEachRecord} and uploads each table from a scratch file, so a
 * test stubs the stream and captures the file's bytes at the moment of the upload — the file is
 * deleted right after.
 */
final class EgressTestSupport {

    static final long ROW_GROUP_BYTES = 8L * 1024 * 1024;

    private EgressTestSupport() {
    }

    static DeltaEgressService egress(ChangelogSegmentRepository repository, ChangelogSegmentService segments,
                                     SiteSchemaService schemas, S3CheckpointStorage storage,
                                     MeterRegistry registry, ApplicationShutdownSignal shutdown,
                                     Path scratch) {
        return new DeltaEgressService(repository, segments, schemas, storage, new DeltaMetrics(registry),
                new DeltaParquetProperties(ROW_GROUP_BYTES), new ParquetScratchBudget(registry, 0L),
                scratch.toString(), Long.MAX_VALUE, 8, 60, 7, 600, shutdown);
    }

    /** The segment {@code key} streams these records, as many times as it is read. */
    static void stubSegment(ChangelogSegmentService segments, String key, List<ChangeRecord> records) {
        lenient().doAnswer(invocation -> {
            Consumer<ChangeRecord> consumer = invocation.getArgument(1);
            records.forEach(consumer);
            return null;
        }).when(segments).forEachRecord(eq(key), any(), any());
    }

    /** Reading the segment {@code key} fails with {@code failure}. */
    static void failSegment(ChangelogSegmentService segments, String key, RuntimeException failure) {
        lenient().doAnswer(invocation -> {
            throw failure;
        }).when(segments).forEachRecord(eq(key), any(), any());
    }

    /**
     * Capture the bytes of every delta file uploaded, keyed by table. The returned map is filled as
     * {@code uploadDelta} is called.
     */
    static Map<String, byte[]> captureUploads(S3CheckpointStorage storage) {
        Map<String, byte[]> uploads = new ConcurrentHashMap<>();
        lenient().doAnswer(invocation -> {
            Path file = invocation.getArgument(4);
            try {
                uploads.put(invocation.getArgument(1), Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return "egress/key";
        }).when(storage).uploadDelta(any(), anyString(), anyLong(), anyLong(), any(Path.class));
        return uploads;
    }
}
