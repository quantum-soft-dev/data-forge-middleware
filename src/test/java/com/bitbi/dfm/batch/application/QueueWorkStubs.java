package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository.PendingQueueWork;

/**
 * Test literals for the {@link PendingQueueWork} projection (issue #212) — an interface, so the
 * two batch-deleter test classes build stubs; hoisted here so each does not grow its own copy
 * (review round 2, R2-9).
 */
final class QueueWorkStubs {

    private QueueWorkStubs() {
    }

    static PendingQueueWork pendingWork(long pluginSql, long egress) {
        return new PendingQueueWork() {
            @Override
            public long getPendingPluginSql() {
                return pluginSql;
            }

            @Override
            public long getPendingEgress() {
                return egress;
            }
        };
    }
}
