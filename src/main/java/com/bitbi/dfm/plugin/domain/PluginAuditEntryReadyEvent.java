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
 * @param entry the audit entry, ready to persist
 */
public record PluginAuditEntryReadyEvent(PluginAuditLog entry) {
}
