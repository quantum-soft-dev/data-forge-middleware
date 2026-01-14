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
    @DisplayName("History Management Action Types")
    class HistoryManagementActionTypes {

        @Test
        @DisplayName("Should have PLUGIN_HISTORY_CLEARED type for logging when history is cleared")
        void shouldHavePluginHistoryClearedType() {
            PluginActionType type = PluginActionType.PLUGIN_HISTORY_CLEARED;
            assertThat(type.name()).isEqualTo("PLUGIN_HISTORY_CLEARED");
        }

        @Test
        @DisplayName("Should have SQL_REGENERATION_STARTED type for logging when regeneration begins")
        void shouldHaveSqlRegenerationStartedType() {
            PluginActionType type = PluginActionType.SQL_REGENERATION_STARTED;
            assertThat(type.name()).isEqualTo("SQL_REGENERATION_STARTED");
        }

        @Test
        @DisplayName("Should have SQL_REGENERATION_COMPLETED type for logging successful regeneration")
        void shouldHaveSqlRegenerationCompletedType() {
            PluginActionType type = PluginActionType.SQL_REGENERATION_COMPLETED;
            assertThat(type.name()).isEqualTo("SQL_REGENERATION_COMPLETED");
        }

        @Test
        @DisplayName("Should have SQL_REGENERATION_FAILED type for logging failed regeneration")
        void shouldHaveSqlRegenerationFailedType() {
            PluginActionType type = PluginActionType.SQL_REGENERATION_FAILED;
            assertThat(type.name()).isEqualTo("SQL_REGENERATION_FAILED");
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

    @Nested
    @DisplayName("Plugin Reinit Action Types - Feature 015")
    class PluginReinitActionTypes {

        @Test
        @DisplayName("Should have REINIT type for logging when plugin SQL state is reinitialized")
        void shouldHaveReinitType() {
            PluginActionType type = PluginActionType.REINIT;
            assertThat(type.name()).isEqualTo("REINIT");
        }
    }

    @Test
    @DisplayName("Should have exactly 15 action types")
    void shouldHaveFifteenActionTypes() {
        // 6 existing + 3 SQL generation types + 4 history management types + 1 reinit type (Feature 015)
        // + 1 SQL_GENERATION_DELETED (manual admin tools)
        // (PLUGIN_HISTORY_CLEARED, SQL_REGENERATION_STARTED, SQL_REGENERATION_COMPLETED, SQL_REGENERATION_FAILED, REINIT, SQL_GENERATION_DELETED)
        assertThat(PluginActionType.values()).hasSize(15);
    }
}
