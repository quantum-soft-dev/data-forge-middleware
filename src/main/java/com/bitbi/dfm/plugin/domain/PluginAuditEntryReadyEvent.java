package com.bitbi.dfm.plugin.domain;

/**
 * Carries a fully built audit entry that must not be written until the publishing transaction
 * commits.
 * <p>
 * Audit writes open their own transaction. Written inline from a caller that is still inside one,
 * the row survives a rollback — so an entry describing a <em>state change</em> can end up asserting
 * something the database undid, which is worse than no entry at all.
 * </p>
 * <p>
 * Only entries that describe committed state changes travel this way. Failures and observations are
 * written immediately and deliberately: {@code logReinitFailed} is called inside a transaction and
 * followed straight by a throw, so deferring it would drop the very record it exists to capture.
 * </p>
 *
 * <p>
 * {@code rollbackEntry} covers the case where a rollback is not a non-event: clear, reinit and
 * delete-generation destroy S3 objects before their transaction commits, and no rollback brings
 * those back. Waiting for the commit is still right — the entry claiming success must not be
 * written — but the divergence a rollback leaves behind (rows restored, files gone) has to be
 * recorded rather than vanish. It stays {@code null} for changes a rollback undoes in full.
 * </p>
 *
 * @param entry         the audit entry, ready to persist once the transaction commits
 * @param rollbackEntry the entry to persist instead when the transaction rolls back, or
 *                      {@code null} when a rollback leaves nothing worth recording
 */
public record PluginAuditEntryReadyEvent(PluginAuditLog entry, PluginAuditLog rollbackEntry) {

    /**
     * A state change that is wholly transactional: a rollback undoes all of it, so there is
     * nothing to record on that path.
     *
     * @param entry the audit entry, ready to persist once the transaction commits
     */
    public PluginAuditEntryReadyEvent(PluginAuditLog entry) {
        this(entry, null);
    }
}
