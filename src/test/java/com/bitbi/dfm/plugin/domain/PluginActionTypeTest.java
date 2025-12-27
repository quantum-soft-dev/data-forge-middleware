package com.bitbi.dfm.plugin.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PluginActionType enum.
 * Verifies all action types are available for audit logging.
 */
@DisplayName("PluginActionType")
class PluginActionTypeTest {

    @Nested
    @DisplayName("SQL Generation Action Types")
    class SqlGenerationActionTypes {

        @Test
        @DisplayName("Should have SQL_GENERATION_STARTED type for logging when SQL generation begins")
        void shouldHaveSqlGenerationStartedType() {
            PluginActionType type = PluginActionType.SQL_GENERATION_STARTED;
            assertThat(type.name()).isEqualTo("SQL_GENERATION_STARTED");
        }

        @Test
        @DisplayName("Should have SQL_GENERATION_COMPLETED type for logging successful SQL generation")
        void shouldHaveSqlGenerationCompletedType() {
            PluginActionType type = PluginActionType.SQL_GENERATION_COMPLETED;
            assertThat(type.name()).isEqualTo("SQL_GENERATION_COMPLETED");
        }

        @Test
        @DisplayName("Should have SQL_GENERATION_FAILED type for logging failed SQL generation")
        void shouldHaveSqlGenerationFailedType() {
            PluginActionType type = PluginActionType.SQL_GENERATION_FAILED;
            assertThat(type.name()).isEqualTo("SQL_GENERATION_FAILED");
        }
    }

    @Nested
    @DisplayName("Existing Action Types")
    class ExistingActionTypes {

        @Test
        @DisplayName("Should have all activation-related types")
        void shouldHaveActivationTypes() {
            assertThat(PluginActionType.ACTIVATE).isNotNull();
            assertThat(PluginActionType.DEACTIVATE).isNotNull();
            assertThat(PluginActionType.REACTIVATE).isNotNull();
        }

        @Test
        @DisplayName("Should have all event dispatch types")
        void shouldHaveEventDispatchTypes() {
            assertThat(PluginActionType.EVENT_DISPATCHED).isNotNull();
            assertThat(PluginActionType.EVENT_FAILED).isNotNull();
            assertThat(PluginActionType.EVENT_TIMEOUT).isNotNull();
        }
    }

    @Test
    @DisplayName("Should have exactly 9 action types")
    void shouldHaveNineActionTypes() {
        // 6 existing + 3 new SQL generation types
        assertThat(PluginActionType.values()).hasSize(9);
    }
}
