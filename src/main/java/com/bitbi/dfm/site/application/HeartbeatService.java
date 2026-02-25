package com.bitbi.dfm.site.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import com.bitbi.dfm.site.presentation.dto.HeartbeatResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Application service for heartbeat pre-batch sync check.
 * <p>
 * Records client heartbeat, returns site status, directives,
 * schema info, and last completed batch info.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US2 - Heartbeat Pre-Batch Sync Check
 */
@Service
@Transactional
public class HeartbeatService {

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatService.class);

    private final SiteRepository siteRepository;
    private final BatchRepository batchRepository;
    private final SiteSchemaRepository siteSchemaRepository;

    public HeartbeatService(SiteRepository siteRepository,
                            BatchRepository batchRepository,
                            SiteSchemaRepository siteSchemaRepository) {
        this.siteRepository = siteRepository;
        this.batchRepository = batchRepository;
        this.siteSchemaRepository = siteSchemaRepository;
    }

    /**
     * Record a heartbeat and return site status with directives.
     *
     * @param siteId site identifier (from JWT token)
     * @return heartbeat response with directives, schema, and last batch info
     * @throws IllegalArgumentException if site not found
     */
    public HeartbeatResponseDto recordHeartbeat(UUID siteId) {
        logger.debug("Recording heartbeat: siteId={}", siteId);

        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new IllegalArgumentException("Site not found: " + siteId));

        // Update heartbeat timestamp
        site.recordHeartbeat();
        siteRepository.save(site);

        // Fetch schema info (nullable)
        SiteSchema schema = siteSchemaRepository.findBySiteId(siteId).orElse(null);

        // Fetch last completed batch (nullable)
        Batch lastCompletedBatch = batchRepository.findLatestCompletedBySiteId(siteId).orElse(null);

        HeartbeatResponseDto response = HeartbeatResponseDto.build(site, schema, lastCompletedBatch);

        logger.info("Heartbeat recorded: siteId={}, status={}, forceFullUpload={}",
                siteId, response.siteStatus(), response.directives().forceFullUpload());

        return response;
    }
}
