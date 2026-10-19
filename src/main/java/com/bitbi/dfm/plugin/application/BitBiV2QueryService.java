package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.plugin.domain.RowIdentity;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.application.SiteService;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.TableSchema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model behind the Bit BI v2 surface, phase 1 part 1 (issue #386; see
 * {@code docs/cr-bitbi-parquet-plugin.md}).
 *
 * <p>Composes the services the rest of the application already reads a site through — the site
 * repository's account-scoped lookups, {@link DeltaSyncStateService} and {@link SiteSchemaService}
 * — and adds only two things of its own: ownership answered as absence, and the per-table
 * {@link RowIdentity}. Nothing is stored and no per-consumer state is read: v2 holds the watermark
 * on the consumer's side.</p>
 *
 * <p><b>Ownership.</b> A site of another account is {@link SiteService.SiteNotFoundException} with
 * the same message as one that does not exist, so a key cannot learn which site ids exist. v1
 * answers 403 there; v2 deliberately does not. A deactivated site of the key's own account is
 * served — as v1's {@code /files} and {@code /sql-changes} serve it — although {@link #listSites}
 * does not list it.</p>
 *
 * <p><b>{@code schemaVersion} has one source.</b> Both {@code /state} and {@code /schema} read
 * {@code site_schemas.schema_version}, the version of the document {@code /schema} serves.
 * {@code site_sync_state.schema_version} is a copy written by a second transaction after the
 * upsert ({@code DeltaIngestionService.submitSchema}), so reading it here would let the two
 * endpoints disagree for that window about the same schema.</p>
 */
@Service
public class BitBiV2QueryService {

    private final SiteRepository siteRepository;
    private final DeltaSyncStateService syncStateService;
    private final SiteSchemaService siteSchemaService;

    public BitBiV2QueryService(SiteRepository siteRepository,
                               DeltaSyncStateService syncStateService,
                               SiteSchemaService siteSchemaService) {
        this.siteRepository = siteRepository;
        this.syncStateService = syncStateService;
        this.siteSchemaService = siteSchemaService;
    }

    /**
     * The account's sites — the same selection as v1's {@code GET /sites}.
     *
     * @param accountId the account the API key belongs to
     * @return the account's active sites
     */
    @Transactional(readOnly = true)
    public List<Site> listSites(UUID accountId) {
        return siteRepository.findActiveByAccountId(accountId);
    }

    /**
     * Where the site's history stands.
     *
     * @param accountId the account the API key belongs to
     * @param siteId    the site
     * @return the site's epoch, checkpoint pointer, applied watermark and schema version
     * @throws SiteService.SiteNotFoundException when the site does not exist or is another account's
     */
    @Transactional(readOnly = true)
    public SiteState state(UUID accountId, UUID siteId) {
        requireOwned(accountId, siteId);
        SyncStateView sync = syncStateService.getSyncState(siteId);
        int schemaVersion = siteSchemaService.findSchema(siteId)
                .map(SiteSchema::getSchemaVersion)
                .orElse(0);
        return new SiteState(siteId, sync.baselineEpoch(), sync.lastCheckpointSeq(),
                sync.lastAppliedSeq(), schemaVersion);
    }

    /**
     * The declared schema with each table's row identity.
     *
     * @param accountId the account the API key belongs to
     * @param siteId    the site
     * @return the schema version and the tables, sorted by name
     * @throws SiteService.SiteNotFoundException when the site does not exist or is another account's
     */
    @Transactional(readOnly = true)
    public SiteSchemaView schema(UUID accountId, UUID siteId) {
        requireOwned(accountId, siteId);
        Optional<SiteSchema> stored = siteSchemaService.findSchema(siteId);
        if (stored.isEmpty()) {
            return new SiteSchemaView(siteId, 0, List.of());
        }
        // JSONB keeps no key order, so the stored order of tables is not one anybody wrote; the
        // answer is sorted so that it is the same on every call.
        List<TableView> tables = siteSchemaService.tableSchemasOf(stored.get()).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(e -> new TableView(e.getKey(), RowIdentity.of(e.getValue().primaryKey()), e.getValue()))
                .toList();
        return new SiteSchemaView(siteId, stored.get().getSchemaVersion(), tables);
    }

    private void requireOwned(UUID accountId, UUID siteId) {
        siteRepository.findByIdAndAccountId(siteId, accountId)
                .orElseThrow(() -> new SiteService.SiteNotFoundException("Site not found: " + siteId));
    }

    /**
     * A site's position.
     *
     * @param siteId         the site
     * @param epoch          {@code site_sync_state.baseline_epoch}: moved by a history wipe and by a
     *                       re-baseline, never sent to the ingestion client. A change means every
     *                       row the consumer holds belongs to a discarded history
     * @param checkpointSeq  the seq the current checkpoint snapshots are built at; 0 = none yet
     * @param lastAppliedSeq the highest seq the server has committed
     * @param schemaVersion  the version of the schema {@code /schema} serves; 0 = none submitted
     */
    public record SiteState(UUID siteId, long epoch, long checkpointSeq, long lastAppliedSeq, int schemaVersion) {
    }

    /**
     * A site's declared schema.
     *
     * @param siteId        the site
     * @param schemaVersion the stored schema's version; 0 when none was submitted
     * @param tables        the tables, sorted by name
     */
    public record SiteSchemaView(UUID siteId, int schemaVersion, List<TableView> tables) {
    }

    /**
     * One declared table.
     *
     * @param table    the table name
     * @param identity how the consumer keys the table's rows
     * @param schema   columns, primary key and unique keys as declared
     */
    public record TableView(String table, RowIdentity identity, TableSchema schema) {
    }
}
