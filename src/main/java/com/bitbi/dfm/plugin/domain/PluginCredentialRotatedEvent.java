package com.bitbi.dfm.plugin.domain;

import java.util.UUID;

/**
 * Raised when a plugin credential has been rotated.
 * <p>
 * Published from inside the rotating transaction and audited only after that transaction commits.
 * Auditing inline would record the rotation from a separate async transaction that commits on its
 * own: if the rotation then rolled back, the audit trail would permanently claim a
 * security-sensitive change that never took effect — evidence pointing the wrong way, which is
 * worse than no evidence at all.
 * </p>
 *
 * @param pluginId   the plugin whose credential was rotated
 * @param accountId  the owning account
 * @param actionType the audit action to record ({@code API_KEY_ROTATED} or {@code PASSWORD_ROTATED})
 */
public record PluginCredentialRotatedEvent(String pluginId, UUID accountId, PluginActionType actionType) {
}
