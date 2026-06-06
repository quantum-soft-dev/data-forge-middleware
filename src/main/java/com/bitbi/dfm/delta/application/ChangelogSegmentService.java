package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Persists a session's accepted change records as an immutable changelog segment
 * (Delta Client v2 — 022): serialize → object storage (bronze) → metadata row.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogSegmentService {

    private final S3ChangelogSegmentStorage storage;
    private final ChangelogSegmentRepository repository;

    public ChangelogSegmentService(S3ChangelogSegmentStorage storage, ChangelogSegmentRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /**
     * Persist the accepted records of a session as a changelog segment.
     *
     * @param siteId   site identifier
     * @param batchId  batch (session) identifier
     * @param mode     session mode (DELTA | FULL_SNAPSHOT)
     * @param firstSeq first sequence of the session
     * @param records  accepted change records (in sequence order)
     * @return the persisted segment metadata
     */
    @Transactional
    public ChangelogSegment persist(UUID siteId, UUID batchId, String mode, long firstSeq, List<ChangeRecord> records) {
        byte[] content = ChangelogCodec.serialize(records);
        String contentHash = sha256Hex(content);
        String s3Key = storage.uploadSegment(siteId, batchId, content);

        long lastSeq = records.isEmpty() ? firstSeq - 1 : records.get(records.size() - 1).getSeq();

        ChangelogSegment segment = ChangelogSegment.create(
                siteId, batchId, firstSeq, lastSeq, records.size(), contentHash, s3Key, mode);
        return repository.save(segment);
    }

    /**
     * Read back the change records of a persisted segment.
     *
     * @param s3Key segment key
     * @return the segment's change records in order
     */
    public List<ChangeRecord> readRecords(String s3Key) {
        return ChangelogCodec.parse(storage.download(s3Key));
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
