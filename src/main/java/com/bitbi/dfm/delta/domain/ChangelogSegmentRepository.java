package com.bitbi.dfm.delta.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ChangelogSegment} persistence (Delta Client v2 — 022).
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface ChangelogSegmentRepository {

    ChangelogSegment save(ChangelogSegment segment);

    Optional<ChangelogSegment> findBySiteIdAndFirstSeq(UUID siteId, long firstSeq);
}
