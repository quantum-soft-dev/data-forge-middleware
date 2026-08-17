package com.bitbi.dfm.plugin.infrastructure.events;

import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one deferred audit entry in a transaction of its own.
 *
 * <p>A separate bean because a {@code @Transactional} method invoked on {@code this} is not
 * proxied: {@link PluginAuditEventListener} has to decide <em>whether</em> to write before any
 * transaction opens, which it cannot do if the transaction is started by its own method's proxy.
 * That check is the whole of issue #171 — see the listener.</p>
 *
 * <p>Package-private, so the only caller is that listener and the check cannot be walked around
 * from elsewhere in the application.</p>
 */
@Component
class PluginAuditEntryWriter {

    private final PluginAuditLogRepository auditLogRepository;

    PluginAuditEntryWriter(PluginAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Persists the entry.
     *
     * <p>{@code REQUIRES_NEW} rather than {@code REQUIRED}: the entry is a record of something that
     * has already resolved, so it belongs to no other unit of work. The listener guarantees no
     * transaction is active on this thread, which makes the two propagations equivalent in every
     * reachable case — the declaration is what the write means, not a suspension anyone relies
     * on.</p>
     *
     * @param entry the audit entry to persist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(PluginAuditLog entry) {
        auditLogRepository.save(entry);
    }
}
