package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.plugin.domain.RowIdentity;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The read model behind the Bit BI v2 surface (issue #386): ownership, the site's epoch and
 * watermarks, and the per-table row identity derived from the declared schema.
 */
@DisplayName("BitBiV2QueryService")
class BitBiV2QueryServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("0199bab2-aaaa-7000-8000-000000000001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("0199bab2-bbbb-7000-8000-000000000002");
    private static final UUID SITE = UUID.fromString("0199bab2-cccc-7000-8000-000000000003");

    private SiteRepository siteRepository;
    private DeltaSyncStateService syncStateService;
    private SiteSchemaRepository schemaRepository;
    private BitBiV2QueryService service;

    @BeforeEach
    void setUp() {
        siteRepository = mock(SiteRepository.class);
        syncStateService = mock(DeltaSyncStateService.class);
        schemaRepository = mock(SiteSchemaRepository.class);
        // The real schema service over a stubbed row, so identity is derived from the stored JSONB
        // shape SubmitSchema writes rather than from a TableSchema built by hand.
        SiteSchemaService schemaService = new SiteSchemaService(schemaRepository, JsonMapper.builder().build());
        service = new BitBiV2QueryService(siteRepository, syncStateService, schemaService);
    }

    private void siteOwnedBy(UUID accountId) {
        Site site = mock(Site.class);
        when(site.getId()).thenReturn(SITE);
        when(site.getAccountId()).thenReturn(accountId);
        when(siteRepository.findByIdAndAccountId(SITE, accountId)).thenReturn(Optional.of(site));
    }

    private void storedSchema(int version, Map<String, Object> tables) {
        SiteSchema schema = SiteSchema.create(SITE, Map.of("tables", tables));
        for (int v = 1; v < version; v++) {
            schema.update(Map.of("tables", tables));
        }
        when(schemaRepository.findBySiteId(SITE)).thenReturn(Optional.of(schema));
    }

    private static Map<String, Object> column(String name, String type, boolean nullable) {
        return Map.of("name", name, "type", type, "nullable", nullable);
    }

    @Nested
    @DisplayName("Ownership")
    class Ownership {

        @Test
        @DisplayName("A site of another account is not found, exactly like one that does not exist")
        void aForeignSiteIsNotFound() {
            siteOwnedBy(OTHER_ACCOUNT);

            assertThatThrownBy(() -> service.state(ACCOUNT, SITE))
                    .isInstanceOf(SiteService.SiteNotFoundException.class)
                    .hasMessage("Site not found: " + SITE);
            assertThatThrownBy(() -> service.schema(ACCOUNT, SITE))
                    .isInstanceOf(SiteService.SiteNotFoundException.class)
                    .hasMessage("Site not found: " + SITE);
            verify(syncStateService, never()).getSyncState(SITE);
            verify(schemaRepository, never()).findBySiteId(SITE);
        }

        @Test
        @DisplayName("A site that does not exist is not found")
        void anAbsentSiteIsNotFound() {
            assertThatThrownBy(() -> service.state(ACCOUNT, SITE))
                    .isInstanceOf(SiteService.SiteNotFoundException.class)
                    .hasMessage("Site not found: " + SITE);
        }

        @Test
        @DisplayName("The site list is the account's active sites, as in v1")
        void listsTheAccountsActiveSites() {
            Site site = mock(Site.class);
            when(siteRepository.findActiveByAccountId(ACCOUNT)).thenReturn(List.of(site));

            assertThat(service.listSites(ACCOUNT)).containsExactly(site);
        }
    }

    @Nested
    @DisplayName("State")
    class State {

        @Test
        @DisplayName("epoch is the baseline epoch — moved by a wipe and by a re-baseline — not the wire generation")
        void epochIsTheBaselineEpoch() {
            siteOwnedBy(ACCOUNT);
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(5_023_845L, 5_020_646L, 2, false, false, 1L, 3L));
            storedSchema(4, Map.of("t", Map.of("columns", List.of(column("a", "text", true)))));

            BitBiV2QueryService.SiteState state = service.state(ACCOUNT, SITE);

            assertThat(state.siteId()).isEqualTo(SITE);
            assertThat(state.epoch()).isEqualTo(3L);
            assertThat(state.checkpointSeq()).isEqualTo(5_020_646L);
            assertThat(state.lastAppliedSeq()).isEqualTo(5_023_845L);
            assertThat(state.schemaVersion())
                    .as("the version /schema serves (site_schemas), not the sync state's copy")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("A site that never synced and has no schema answers zeros")
        void aSiteThatNeverSyncedAnswersZeros() {
            siteOwnedBy(ACCOUNT);
            when(syncStateService.getSyncState(SITE))
                    .thenReturn(new SyncStateView(0L, 0L, 0, false, false, 0L, 0L));

            BitBiV2QueryService.SiteState state = service.state(ACCOUNT, SITE);

            assertThat(state.epoch()).isZero();
            assertThat(state.checkpointSeq()).isZero();
            assertThat(state.lastAppliedSeq()).isZero();
            assertThat(state.schemaVersion()).isZero();
        }
    }

    @Nested
    @DisplayName("Schema")
    class Schema {

        @Test
        @DisplayName("Each table states its identity from its declared primary key, tables sorted by name")
        void eachTableStatesItsIdentity() {
            siteOwnedBy(ACCOUNT);
            storedSchema(2, Map.of(
                    "orders", Map.of(
                            "columns", List.of(column("id", "bigint", false), column("total", "numeric(12,2)", true)),
                            "primaryKey", List.of("id"),
                            "uniqueKeys", List.of(Map.of("name", "uk_orders_total", "columns", List.of("total")))),
                    "nsftrns", Map.of(
                            "columns", List.of(column("TR_NUM", "integer", true)),
                            "primaryKey", List.of())));

            BitBiV2QueryService.SiteSchemaView view = service.schema(ACCOUNT, SITE);

            assertThat(view.siteId()).isEqualTo(SITE);
            assertThat(view.schemaVersion()).isEqualTo(2);
            assertThat(view.tables()).extracting(BitBiV2QueryService.TableView::table)
                    .containsExactly("nsftrns", "orders");
            BitBiV2QueryService.TableView nsftrns = view.tables().get(0);
            assertThat(nsftrns.identity()).isEqualTo(RowIdentity.ROW_HASH);
            assertThat(nsftrns.schema().primaryKey()).isEmpty();
            BitBiV2QueryService.TableView orders = view.tables().get(1);
            assertThat(orders.identity()).isEqualTo(RowIdentity.PRIMARY_KEY);
            assertThat(orders.schema().primaryKey()).containsExactly("id");
            assertThat(orders.schema().uniqueKeys()).singleElement()
                    .satisfies(uk -> assertThat(uk.columns()).containsExactly("total"));
            assertThat(orders.schema().columns()).extracting(c -> c.name() + ":" + c.type() + ":" + c.nullable())
                    .containsExactly("id:bigint:false", "total:numeric(12,2):true");
        }

        @Test
        @DisplayName("A table that declares no primaryKey at all is keyed by _row_hash")
        void aTableWithoutAPrimaryKeyMemberIsRowHash() {
            siteOwnedBy(ACCOUNT);
            storedSchema(1, Map.of("fiattime", Map.of("columns", List.of(column("F", "text", true)))));

            assertThat(service.schema(ACCOUNT, SITE).tables()).singleElement()
                    .satisfies(t -> assertThat(t.identity()).isEqualTo(RowIdentity.ROW_HASH));
        }

        @Test
        @DisplayName("A site with no submitted schema answers version 0 and no tables")
        void noSchemaAnswersVersionZero() {
            siteOwnedBy(ACCOUNT);

            BitBiV2QueryService.SiteSchemaView view = service.schema(ACCOUNT, SITE);

            assertThat(view.schemaVersion()).isZero();
            assertThat(view.tables()).isEmpty();
        }
    }
}
