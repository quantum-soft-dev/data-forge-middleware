# 042 — Parquet / checkpoint duration phases

Issue #111. Operators cannot tell whether a Parquet materialization cycle is spent on S3,
protobuf decode, decimal scan, encode, or upload. The existing timers wrap the whole
pipeline; per-segment egress has only a counter.

## Requirements

- Keep the Micrometer meter names. Do not add new root names except
  `delta.egress.duration` (egress has no timer today) and `delta.egress.pending`.
- Record a `phase` tag on each duration sample:

  | Meter | Phases |
  |---|---|
  | `delta.batch-parquet.duration` | `download`, `decode`, `decimal_scan`, `write`, `upload` |
  | `delta.egress.duration` | `download`, `write`, `upload` |
  | `delta.checkpoint.duration` | `download_frame`, `fold`, `parquet`, `upload` |

- Keep the existing untagged timer for the whole cycle (`timeBatchParquetBuild`,
  `timeCheckpoint`, and a new untagged egress total) so `write / cycle` is one PromQL
  ratio and existing dashboards keep working.
- Attribute streaming replay without changing writer heap behaviour: download is time
  in `GetObject` / stream `read`, decode is parse time excluding the record consumer,
  `decimal_scan` / `write` are only the scan consumer and the Parquet writer.
- Export `delta.egress.pending` as a live `COUNT(*)` of `changelog_segments` where
  `egress_at IS NULL` (the durable egress queue, including any parked provisional
  rows that still have a null marker). Reuse the 5 s snapshot TTL pattern of
  `delta.batch-parquet.queue`.
- Pre-register every known phase timer so `/actuator/prometheus` shows the series
  at count zero before the first build.
- Writers, queues, S3 keys, REST, gRPC, protobuf, and frontend stay unchanged.

## Reading rule

Using Cloud Monitoring / PromQL on the exported `_sum` series of one scrape window:

- `write` < 20% of the untagged cycle → encode is not the bottleneck.
- 20–50% → stay on Java (file-backed checkpoint, smaller row-group) before changing
  the writer stack.
- > 50% while S3 phases (`download` / `upload` / `download_frame`) look normal →
  then a native renderer is worth discussing.

## Compatibility

- No migration. No configuration keys. No metric *name* change for the two
  existing timers; they gain a `phase` tag on the new series only.
- Tag values are an allowlist. Unknown phases are rejected so cardinality cannot
  drift from a typo.
