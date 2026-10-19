# Tasks — 044 Bit BI plugin v2

## Phase 1, part 1 — #386

- [x] T01 `RowIdentity` derived from the declared primary key — `RowIdentityTest`
- [x] T02 `BitBiV2QueryService`: ownership as absence, epoch = baseline epoch, one source for `schemaVersion`, tables sorted — `BitBiV2QueryServiceTest`
- [x] T03 `BitBiV2ApiController` + DTOs + `ApiRoutes.BITBI_V2_*`: `/v2/sites`, `/state`, `/schema`, 404, 429 — `BitBiV2ApiContractTest` (production chain, `WireJson`)
- [x] T04 Production chain: an unknown path under `/v2` is 401 / 404 — `ProductionSecurityChainTest`
- [x] T05 Wired: epoch over a wipe and a re-baseline, revoked key, stored schema — `BitBiV2StateIntegrationTest`
- [x] T06 Docs: CR committed, `docs/bitbi-integration.md` "Bit BI API v2", journals

## Phase 1, part 2 — #387

- [ ] T07 `GET /v2/sites/{siteId}/snapshot`: one checkpoint Parquet per table, all at `checkpointSeq`, with `fileId`
- [ ] T08 `GET /v2/files/{fileId}`: `302` to a short-lived presigned URL; no download through the heap
- [ ] T09 Contract tests on the production chain; guide examples

## Phase 2 — #388

- [ ] T10 V61: the epoch a batch belongs to, stored with the batch; seq index for the catalog
- [ ] T11 Catalog query with an `afterSeq` predicate, in batch order (inherits #378/#380)
- [ ] T12 Per-batch table manifest from `batches.table_stats`: `ready` / `abandoned` / `missing`
- [ ] T13 `GET /v2/sites/{siteId}/changes?afterSeq=&cursor=&size=`; contract and integration tests; guide
