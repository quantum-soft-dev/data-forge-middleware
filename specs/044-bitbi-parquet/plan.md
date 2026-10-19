# Plan — 044 Bit BI plugin v2

## Shape

One plugin, one key, a second surface: a new `BitBiV2ApiController` beside
`BitBiPluginApiController`, over read services the application already has. No domain is forked
and no per-consumer state is added: v2 reads neither `baseline_batch_id` nor
`plugin_delta_baselines`.

| Layer | Phase 1 part 1 (#386) | Later |
|---|---|---|
| domain | `plugin/domain/RowIdentity` (derived from `primaryKey`) | — |
| application | `plugin/application/BitBiV2QueryService`: ownership (absent ≡ foreign → `SiteService.SiteNotFoundException`), `DeltaSyncStateService.getSyncState`, `SiteSchemaService.findSchema` + `tableSchemasOf` | #387: `CheckpointFileQueryService` for the snapshot listing; #388: `ParquetExportCatalogQuery` with a seq predicate |
| presentation | `BitBiV2ApiController`, `BitBiV2SiteListDto`, `BitBiV2SiteStateDto`, `BitBiV2SiteSchemaDto`; routes in `ApiRoutes.BITBI_V2_*` | `/snapshot`, `/files/{fileId}`, `/changes` |
| security | none — `BitBiPluginApiPaths.KEY_AUTHENTICATED` already owns `/api/v1/plugins/bit-bi/**` (#384) | an Auth0 route under the namespace would need the exclusion |
| database | none | **V61** (#388): the epoch a batch belongs to, stored with the batch, and a seq index |

## Decisions taken in phase 1 part 1

See the CR, decisions 13–18: epoch source, one source for `schemaVersion`, 404 for a foreign site,
no security change, the 429 body, a deactivated own site.

## Tests

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
