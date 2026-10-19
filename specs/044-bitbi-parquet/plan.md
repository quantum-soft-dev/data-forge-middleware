# Plan — 044 Bit BI plugin v2

## Shape

One plugin, one key, a second surface: a new `BitBiV2ApiController` beside
`BitBiPluginApiController`, over read services the application already has. No domain is forked
and no per-consumer state is added: v2 reads neither `baseline_batch_id` nor
`plugin_delta_baselines`.

| Layer | Phase 1 part 1 (#386) | Later |
|---|---|---|
| domain | `plugin/domain/RowIdentity` (derived from `primaryKey`) | — |
| domain | | #387: `plugin/domain/BitBiFileId` (the `ck1` kind) |
| application | `plugin/application/BitBiV2QueryService`: ownership (absent ≡ foreign → `SiteService.SiteNotFoundException`), `DeltaSyncStateService.getSyncState`, `SiteSchemaService.findSchema` + `tableSchemasOf` | #387: `snapshotSource` / `isResolvable` (read-only, database) and `BitBiV2FileService` (HEAD + presign outside any transaction, over `S3CheckpointStorage` and `S3PresignedUrlService`); `CheckpointFileQueryService` is v1's and is not used — its 403 and its historical-CSV fallback are not v2's contract. #388: `ParquetExportCatalogQuery` with a seq predicate |
| presentation | `BitBiV2ApiController`, `BitBiV2SiteListDto`, `BitBiV2SiteStateDto`, `BitBiV2SiteSchemaDto`; routes in `ApiRoutes.BITBI_V2_*` | #387: `/snapshot` (`BitBiV2SnapshotDto`), `/files/{fileId}` (302, 404 handled in the controller); #388: `/changes` |
| security | none — `BitBiPluginApiPaths.KEY_AUTHENTICATED` already owns `/api/v1/plugins/bit-bi/**` (#384) | an Auth0 route under the namespace would need the exclusion |
| database | none (#387 neither: the `fileId` needs no table) | **V61** (#388): the epoch a batch belongs to, stored with the batch, and a seq index |

## Decisions taken in phase 1 part 1

See the CR, decisions 13–18: epoch source, one source for `schemaVersion`, 404 for a foreign site,
no security change, the 429 body, a deactivated own site.

## Tests

Part 2 (#387): `BitBiFileIdTest`, `BitBiV2FileServiceTest` (the build window, given-up tables,
epoch, ownership, gone objects, undecidable storage), the `Snapshot` and `Files` groups of
`BitBiV2ApiContractTest`, and `BitBiV2SnapshotFilesIntegrationTest` on LocalStack (the redirect
opens the listed bytes through a build and after it). Part 1:

- Unit: `RowIdentityTest`, `BitBiV2QueryServiceTest` (real `SiteSchemaService` over a stubbed
  `site_schemas` row).
- Contract, production chain on a real Tomcat: `BitBiV2ApiContractTest` (harness of
  `ProductionSecurityChainTest`), every body pinned whole with `WireJson`; the bodies are the
  guide's examples.
- Integration (Testcontainers): `BitBiV2StateIntegrationTest` — epoch over a real wipe and a real
  re-baseline reset, a key rotated away and an activation deactivated, identity from a schema stored
  through `SiteSchemaService.upsertSchema`, foreign site against the real repository.

## Collisions

No Flyway number in phase 1; phase 2 takes V61 and must not run beside another ticket that does.
`docs/bitbi-integration.md` is accumulating.
