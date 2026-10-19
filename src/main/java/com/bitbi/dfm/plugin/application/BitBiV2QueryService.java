package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.application.DeltaSyncStateService;
import com.bitbi.dfm.delta.application.DeltaSyncStateService.SyncStateView;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
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
    private final CheckpointRepository checkpointRepository;

    public BitBiV2QueryService(SiteRepository siteRepository,
                               DeltaSyncStateService syncStateService,
                               SiteSchemaService siteSchemaService,
                               CheckpointRepository checkpointRepository) {
        this.siteRepository = siteRepository;
        this.syncStateService = syncStateService;
        this.siteSchemaService = siteSchemaService;
        this.checkpointRepository = checkpointRepository;
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

    /**
     * What {@code /snapshot} is built from (issue #387): the site's epoch, its checkpoint pointer and
     * where each table's checkpoint row stands now. Database only — the object-storage half runs in
     * {@link BitBiV2FileService} after this transaction has returned (#164).
     *
     * @param accountId the account the API key belongs to
     * @param siteId    the site
     * @return the epoch, the pointer and the rows, sorted by table; no rows while the pointer is 0
     * @throws SiteService.SiteNotFoundException when the site does not exist or is another account's
     */
    @Transactional(readOnly = true)
    public SnapshotSource snapshotSource(UUID accountId, UUID siteId) {
        requireOwned(accountId, siteId);
        SyncStateView sync = syncStateService.getSyncState(siteId);
        if (sync.lastCheckpointSeq() <= 0) {
            // No checkpoint yet (or a wipe/re-baseline reset the pointer): rows a first build is
            // writing describe no seq the consumer could stitch changes to.
            return new SnapshotSource(siteId, sync.baselineEpoch(), 0L, List.of());
        }
        List<TableVersion> tables = checkpointRepository.findBySiteId(siteId).stream()
                .map(cp -> new TableVersion(cp.getTableName(), cp.getSeq(), cp.getS3KeyParquet()))
                .sorted(Comparator.comparing(TableVersion::table))
                .toList();
        return new SnapshotSource(siteId, sync.baselineEpoch(), sync.lastCheckpointSeq(), tables);
    }

    /**
     * Whether a checkpoint {@code fileId} still names a file this account may read (issue #387):
     * the site is the account's, the id's epoch is the site's current one, and the table still has
     * a checkpoint row. The object itself is checked by the caller, outside the transaction.
     *
     * @param accountId the account the API key belongs to
     * @param id        the decoded id
     * @return true when the id may be resolved to its object
     */
    @Transactional(readOnly = true)
    public boolean isResolvable(UUID accountId, BitBiFileId.Checkpoint id) {
        if (siteRepository.findByIdAndAccountId(id.siteId(), accountId).isEmpty()) {
            return false;
        }
        if (syncStateService.getSyncState(id.siteId()).baselineEpoch() != id.epoch()) {
            return false;
        }
        // The table must be one the site has, not merely a string that fits into a key.
        return checkpointRepository.findBySiteIdAndTableName(id.siteId(), id.table()).isPresent();
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
     * The database half of a snapshot listing.
     *
     * @param siteId        the site
     * @param epoch         the site's baseline epoch
     * @param checkpointSeq the checkpoint pointer every listed file is at; 0 = no checkpoint yet
     * @param tables        each table's checkpoint row as it stands now, sorted by table
     */
    public record SnapshotSource(UUID siteId, long epoch, long checkpointSeq, List<TableVersion> tables) {
    }

    /**
     * One table's checkpoint row as it stands now.
     *
     * @param table the table
     * @param seq   the seq the row was last written at — above the pointer while a build runs, or
     *              after a build that ended part way
     * @param s3Key the key of the snapshot at {@code seq}; null when it is not materialized
     */
    public record TableVersion(String table, long seq, String s3Key) {
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
