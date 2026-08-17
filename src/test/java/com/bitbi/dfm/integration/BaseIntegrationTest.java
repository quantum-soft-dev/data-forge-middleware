package com.bitbi.dfm.integration;

import com.bitbi.dfm.auth.domain.JwtToken;
import com.bitbi.dfm.auth.infrastructure.JwtTokenProvider;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.shared.storage.S3PrefixListing;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.util.UUID;

/**
 * Base class for integration tests with MockMvc support.
 * <p>
 * Extends AbstractIntegrationTest to inherit Testcontainers configuration.
 * Provides common utilities for HTTP integration tests:
 * - MockMvc for HTTP request testing
 * - Test data loaded from test-data.sql
 * - JWT token generation helpers
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@AutoConfigureMockMvc
@Sql("/test-data.sql")
public abstract class BaseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @Autowired
    protected SiteRepository siteRepository;

    /**
     * Generate JWT token for test site.
     * <p>
     * Default site: store-01.example.com (the site that owns test batches)
     * Site ID: 0199baac-f852-753f-6fc3-7c994fc38654
     * </p>
     *
     * @return Bearer token string
     */
    protected String generateTestToken() {
        return generateToken("store-01.example.com");
    }

    /**
     * Generate JWT token for specific site.
     *
     * @param domain site domain
     * @return Bearer token string
     */
    protected String generateToken(String domain) {
        Site site = siteRepository.findByDomain(domain)
                .orElseThrow(() -> new IllegalArgumentException("Test site not found: " + domain));
        JwtToken token = jwtTokenProvider.generateToken(site.getId(), site.getAccountId());
        return "Bearer " + token.token();
    }

    @Autowired
    private S3CheckpointStorage egressCleanupStorage;

    @Autowired
    private S3Client egressCleanupS3Client;

    @Value("${s3.bucket.name}")
    private String egressCleanupBucket;

    /**
     * Delete every delta Parquet under a site's egress prefix. The LocalStack bucket is shared
     * across the whole suite, so any test that uploads (or asserts on) {@code egress/{siteId}/...}
     * must purge in {@code @BeforeEach} — otherwise assertions pass or fail depending on what
     * other test classes left behind (review r3: DeltaEgressWorkerIntegrationTest could go
     * false-green on another test's leftover file).
     *
     * @param siteId site whose egress prefix to purge
     */
    protected void purgeEgressPrefix(UUID siteId) {
        purgePrefix(S3CheckpointStorage.egressPrefix(siteId));
    }

    /**
     * Delete every checkpoint object of a site — the per-table snapshots of every build and the
     * {@code _frame/} reload frames. Same reason as {@link #purgeEgressPrefix(UUID)}: the bucket is
     * shared by the whole suite and checkpoint keys carry a sequence number, not a run identity, so
     * a leftover from another class would decide any assertion made on the prefix (issue #118).
     *
     * @param siteId site whose checkpoint prefix to purge
     */
    protected void purgeCheckpointPrefix(UUID siteId) {
        purgePrefix(S3CheckpointStorage.checkpointPrefix(siteId));
    }

    /**
     * Delete every changelog segment object of a site. Same reason as
     * {@link #purgeCheckpointPrefix(UUID)}: the keys carry a segment id, not a run identity, so a
     * class asserting on what is (or is no longer) under {@code delta/{siteId}/segments/} has to
     * start from a known-empty prefix (issue #158).
     *
     * @param siteId site whose segment prefix to purge
     */
    protected void purgeSegmentPrefix(UUID siteId) {
        purgePrefix(S3ChangelogSegmentStorage.segmentPrefix(siteId));
    }

    private void purgePrefix(String prefix) {
        S3PrefixListing listing = egressCleanupStorage.listPrefix(prefix);
        if (listing.truncated()) {
            throw new IllegalStateException("Could not list " + prefix + " to purge leftovers");
        }
        for (String key : listing.keys()) {
            egressCleanupS3Client.deleteObject(
                    DeleteObjectRequest.builder().bucket(egressCleanupBucket).key(key).build());
        }
    }
}
