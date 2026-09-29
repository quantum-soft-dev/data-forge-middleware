# Tasks — 043 row hash (#369)

- [x] T01 Proto: `ChangeRecord.row_hash = 7`; `RowHash` helper (present = 32 bytes, lowercase hex) — `RowHashTest`
- [x] T02 Ingestion: drop a malformed value, one WARN per session, content hash untouched — `DeltaIngestionRowHashContractTest`
- [x] T03 Segments: codec round trip; content hash excludes the field — `RowHashPropagationTest.Segments`
- [x] T04 Fold: `FoldedRow.rowHash` and its footprint — `RowHashPropagationTest.Fold`, `ChangelogFoldFootprintTest`
- [x] T05 Frames: fold, streamed bootstrap, merge (incl. partitions, recreated) — `RowHashPropagationTest.Frames`, `ChangelogMergeEquivalenceTest`
- [x] T06 Parquet: `_row_hash` in delta, batch and snapshot files; egress — `RowHashPropagationTest.Parquet`, `ParquetSchemaMapperTest`
- [x] T07 SQL path unchanged by the field — `DeltaSqlGenerationRowHashTest`
- [x] T08 Wired: all three frame producers and their snapshots on LocalStack — `CheckpointRowHashIntegrationTest`
- [x] T09 Docs: client guide, CR (en/ru), Parquet Export guide, Bit BI guide, journals
