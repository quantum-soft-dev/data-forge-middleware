# Tasks — 044 Bit BI plugin v2

## Phase 1, part 1 — #386

- [x] T01 `RowIdentity` derived from the declared primary key — `RowIdentityTest`
- [x] T02 `BitBiV2QueryService`: ownership as absence, epoch = baseline epoch, one source for `schemaVersion`, tables sorted — `BitBiV2QueryServiceTest`
- [x] T03 `BitBiV2ApiController` + DTOs + `ApiRoutes.BITBI_V2_*`: `/v2/sites`, `/state`, `/schema`, 404, 429 — `BitBiV2ApiContractTest` (production chain, `WireJson`)
- [x] T04 Production chain: an unknown path under `/v2` is 401 / 404 — `ProductionSecurityChainTest`
- [x] T05 Wired: epoch over a wipe and a re-baseline, revoked key, stored schema — `BitBiV2StateIntegrationTest`
- [x] T06 Docs: CR committed, `docs/bitbi-integration.md` "Bit BI API v2", journals

## Phase 1, part 2 — #387

- [x] T07 `BitBiFileId`: opaque, version-pinned id `(siteId, epoch, seq, table)`; key derived through `S3CheckpointStorage.snapshotKey` — `BitBiFileIdTest`, `S3CheckpointStorageUploadTest`
- [x] T08 `GET /v2/sites/{siteId}/snapshot`: one checkpoint Parquet per table, all at `checkpointSeq`, stays there through a build; `missing` listed — `BitBiV2FileServiceTest`
- [x] T09 `GET /v2/files/{fileId}`: `302` to a 60 s presigned URL; 404 for foreign / discarded epoch / gone; 503 when storage cannot say — `BitBiV2FileServiceTest`
- [x] T10 Contract tests on the production chain (`WireJson`, 302, 404, 429, 503) — `BitBiV2ApiContractTest`
- [x] T11 Wired on LocalStack: the redirect opens the listed bytes, through a build and after it; 404 once gone, after a wipe, for another account — `BitBiV2SnapshotFilesIntegrationTest`
- [x] T12 Docs: guide "Bit BI API v2", spec, CR decisions 19–22, journals

## Phase 2 — #388

- [x] T13 V61: the epoch a batch belongs to, stored with the batch (stamped with each recorded segment, after the reset); backfill of the current epoch; seq index — `BatchBaselineEpochMigrationIntegrationTest`, `DeltaSessionCommitTransactionIntegrationTest`, `DeltaSessionCommitServiceTest`, `BatchLifecycleServiceTest`
- [x] T14 Feed query with an `afterSeq` predicate within the consumer's `epoch`, in batch order, ending at the build queue's head (inherits #378/#380) — `BitBiV2ChangesIntegrationTest`
- [x] T15 Per-batch table manifest from `batches.table_stats` and the artifact rows: `ready` / `abandoned` / `missing`; `fileId` kind `bp1`; strict `decode` — `BitBiV2ChangesIntegrationTest`, `BitBiFileIdTest`, `BitBiV2FileServiceTest`
- [x] T16 `GET /v2/sites/{siteId}/changes?epoch=&afterSeq=&cursor=&size=`; cursor `BitBiChangesCursor`; contract tests (`BitBiV2ApiContractTest`), guide, CR decisions 23–28
