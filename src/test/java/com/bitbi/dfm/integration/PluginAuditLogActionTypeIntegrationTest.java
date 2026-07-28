package com.bitbi.dfm.integration;

import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginAuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the enum-to-constraint invariant for {@code plugin_audit_logs.action_type}.
 * <p>
 * The column carries a {@code CHECK ... IN (...)} constraint listing every accepted action type,
 * so adding a value to {@link PluginActionType} without a matching migration produces a row the
 * database refuses. That failure is invisible in production: every audit write in
 * {@code PluginAuditService} is wrapped in a catch-all so audit logging can never break the
 * operation it records. The result is a silently missing audit trail, which is exactly the thing
 * an audit trail must not be.
 * </p>
 */
class PluginAuditLogActionTypeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PluginAuditLogRepository auditLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("every declared action type survives a round trip to the database")
    void shouldPersistEveryDeclaredActionType() {
        UUID accountId = UUID.randomUUID();
        List<String> rejected = new ArrayList<>();

        for (PluginActionType actionType : PluginActionType.values()) {
            try {
                auditLogRepository.save(PluginAuditLog.success("audit-invariant-test", accountId, actionType));
            } catch (Exception e) {
                rejected.add(actionType.name());
            }
        }

        assertThat(rejected)
                .as("action types PluginActionType declares but the database rejects")
                .isEmpty();
    }

    @Test
    @DisplayName("the check constraint accepts exactly the declared action types")
    void shouldKeepCheckConstraintInSyncWithTheEnum() {
        // Every partition carries its own inherited copy under the same name, so the lookup has
        // to be pinned to the partitioned parent.
        String constraint = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = ? AND conrelid = 'plugin_audit_logs'::regclass",
                String.class,
                "chk_plugin_audit_logs_action_type");

        assertThat(constraint).as("constraint must exist").isNotNull();
        for (PluginActionType actionType : PluginActionType.values()) {
            assertThat(constraint)
                    .as("check constraint must allow %s", actionType)
                    .contains("'" + actionType.name() + "'");
        }
    }
}
