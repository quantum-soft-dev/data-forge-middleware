/**
 * CheckpointsCard — per-table checkpoint list with download pills (023, F7).
 *
 * Design handoff v2 §1c: Table|Cards toggle; table grid `1.4fr .8fr 1fr 1fr 1fr`;
 * amber stale pill (>24h); Files pills — Parquet primary (blue-50), dashed
 * non-clickable "Parquet pending" when only CSV exists, muted legacy CSV with
 * tooltip, em-dash when neither. Every click requests a FRESH presigned URL.
 * Client-side name filter appears beyond 15 tables. Rebuild button: canManage.
 */

import { useMemo, useState } from 'react';
import { RefreshCw, Table as TableIcon } from 'lucide-react';
import { formatNumber, formatRelativeTime } from '@/shared/lib/formatters';
import type { DeltaCheckpoint, DeltaCheckpointFormat } from '../model/types';
import { monitoringTokens as t, severityTokens } from '../model/tokens';

const STALE_AFTER_MS = 24 * 60 * 60 * 1000;
const FILTER_THRESHOLD = 15;

type CheckpointView = 'table' | 'cards';

interface CheckpointsCardProps {
  checkpoints: DeltaCheckpoint[];
  canManage: boolean;
  /** Requests a fresh presigned URL and opens it (widget-provided). */
  onDownload: (tableName: string, format: DeltaCheckpointFormat) => void;
  /** Opens the rebuild confirmation (F9); rendered only when canManage. */
  onRebuild?: () => void;
  /** True while the backend rebuild_requested flag is set — disables the button. */
  rebuildQueued?: boolean;
  /** Injectable clock for deterministic tests. */
  now?: Date;
}

export function CheckpointsCard({
  checkpoints,
  canManage,
  onDownload,
  onRebuild,
  rebuildQueued = false,
  now = new Date(),
}: CheckpointsCardProps) {
  const [view, setView] = useState<CheckpointView>('table');
  const [filter, setFilter] = useState('');

  const showFilter = checkpoints.length > FILTER_THRESHOLD;
  const visible = useMemo(() => {
    if (!showFilter || filter.trim() === '') return checkpoints;
    const needle = filter.trim().toLowerCase();
    return checkpoints.filter((checkpoint) => checkpoint.table.toLowerCase().includes(needle));
  }, [checkpoints, filter, showFilter]);

  const maxRows = Math.max(...checkpoints.map((checkpoint) => checkpoint.rowCount), 1);
  const isStale = (checkpoint: DeltaCheckpoint) =>
    now.getTime() - new Date(checkpoint.updatedAt).getTime() > STALE_AFTER_MS;

  return (
    <div className="rounded-[10px] bg-white p-4" style={{ boxShadow: t.cardShadow }} data-testid="checkpoints-card">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div
            className="flex h-9 w-9 items-center justify-center rounded-full bg-white border"
            style={{ borderColor: t.iconWellBorder, boxShadow: t.iconCircleShadow }}
          >
            <TableIcon className="h-4 w-4" style={{ color: t.primary }} strokeWidth={1.5} />
          </div>
          <div>
            <div className="text-[15px] font-medium" style={{ color: t.title, letterSpacing: '-0.24px' }}>
              Checkpoints
            </div>
            <div className="text-xs" style={{ color: t.textMuted }}>
              Materialized snapshot per table · download links valid 15 minutes
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {showFilter && (
            <input
              type="text"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              placeholder="Filter tables…"
              aria-label="Filter tables"
              className="h-8 rounded-lg border px-2 text-sm"
              style={{ borderColor: t.border }}
            />
          )}
          {/* Table | Cards segmented toggle */}
          <div className="flex rounded-lg p-0.5" style={{ background: 'rgba(0,0,0,0.04)' }} role="tablist">
            {(['table', 'cards'] as const).map((option) => (
              <button
                key={option}
                type="button"
                role="tab"
                aria-selected={view === option}
                onClick={() => setView(option)}
                className="rounded-md px-2.5 py-1 text-xs font-medium capitalize transition-colors"
                style={
                  view === option
                    ? { background: '#FFFFFF', color: t.text, boxShadow: '0 1px 2px rgba(0,0,0,0.08)' }
                    : { color: t.textSecondary }
                }
              >
                {option}
              </button>
            ))}
          </div>
          {canManage && (
            <button
              type="button"
              onClick={onRebuild}
              disabled={rebuildQueued}
              className="inline-flex h-8 items-center gap-1.5 rounded-lg border px-3 text-sm font-medium transition-colors hover:bg-[#F5F5F4] disabled:opacity-50"
              style={{ borderColor: t.border, color: t.text }}
            >
              <RefreshCw className="h-3.5 w-3.5" strokeWidth={1.5} />
              Rebuild checkpoint now
            </button>
          )}
        </div>
      </div>

      {/* Body */}
      {visible.length === 0 ? (
        <p className="mt-4 text-sm" style={{ color: t.textMuted }}>
          {checkpoints.length === 0 ? 'No checkpoints yet.' : 'No tables match the filter.'}
        </p>
      ) : view === 'table' ? (
        <div className="mt-3 overflow-x-auto">
          <div className="min-w-[560px]">
            <div
              className="grid px-2 py-1.5 text-xs font-medium"
              style={{ gridTemplateColumns: '1.4fr .8fr 1fr 1fr 1fr', color: t.textSecondary }}
            >
              <span>Table</span>
              <span className="text-right">Seq</span>
              <span className="text-right">Rows</span>
              <span>Last updated</span>
              <span>Files</span>
            </div>
            {visible.map((checkpoint) => (
              <div
                key={checkpoint.table}
                className="grid items-center border-t px-2 text-sm hover:bg-[#FAFAFA]"
                style={{
                  gridTemplateColumns: '1.4fr .8fr 1fr 1fr 1fr',
                  borderColor: t.separator,
                  minHeight: 40,
                  color: t.text,
                }}
                data-testid={`checkpoint-row-${checkpoint.table}`}
              >
                <span className="flex items-center gap-2 font-medium">
                  {checkpoint.table}
                  {isStale(checkpoint) && <StalePill />}
                </span>
                <span className="text-right" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {formatNumber(checkpoint.seq)}
                </span>
                <span className="text-right" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {formatNumber(checkpoint.rowCount)}
                </span>
                <span style={{ color: isStale(checkpoint) ? severityTokens.elevated.text : t.textSecondary }}>
                  {formatRelativeTime(checkpoint.updatedAt)}
                </span>
                <FilePills checkpoint={checkpoint} onDownload={onDownload} />
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="checkpoints-cards-view">
          {visible.map((checkpoint) => (
            <div
              key={checkpoint.table}
              className="rounded-[10px] border p-3"
              style={{ borderColor: t.border }}
            >
              <div className="flex items-center gap-2 text-sm font-medium" style={{ color: t.text }}>
                {checkpoint.table}
                {isStale(checkpoint) && <StalePill />}
              </div>
              <div className="mt-1 flex items-baseline gap-1">
                <span
                  className="text-[22px] font-medium"
                  style={{ color: t.text, letterSpacing: '-0.33px', fontVariantNumeric: 'tabular-nums' }}
                >
                  {formatNumber(checkpoint.rowCount)}
                </span>
                <span className="text-xs" style={{ color: t.textSecondary }}>
                  rows
                </span>
              </div>
              {/* Relative-weight bar */}
              <div className="mt-2 h-1 rounded-full" style={{ background: t.subtleBg }}>
                <div
                  className="h-1 rounded-full"
                  style={{
                    width: `${(checkpoint.rowCount / maxRows) * 100}%`,
                    background: t.primary,
                  }}
                />
              </div>
              <div className="mt-2 text-xs" style={{ color: t.textSecondary, fontVariantNumeric: 'tabular-nums' }}>
                seq {formatNumber(checkpoint.seq)} · {formatRelativeTime(checkpoint.updatedAt)}
              </div>
              <div className="mt-2">
                {checkpoint.hasCsv || checkpoint.hasParquet ? (
                  <FilePills checkpoint={checkpoint} onDownload={onDownload} />
                ) : (
                  <span className="text-xs" style={{ color: t.textMuted }}>
                    no files yet
                  </span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function StalePill() {
  return (
    <span
      className="rounded-full px-1.5 py-0.5 text-[11px] font-medium"
      style={{ background: severityTokens.elevated.bg, color: severityTokens.elevated.text }}
    >
      stale
    </span>
  );
}

/**
 * Files cell — Parquet is the primary/target format, CSV the legacy one (D1).
 * The pill row is a flex with gap, so the layout survives CSV's future removal.
 */
function FilePills({
  checkpoint,
  onDownload,
}: {
  checkpoint: DeltaCheckpoint;
  onDownload: (tableName: string, format: DeltaCheckpointFormat) => void;
}) {
  if (!checkpoint.hasCsv && !checkpoint.hasParquet) {
    return <span style={{ color: t.textMuted }}>—</span>;
  }
  return (
    <span className="flex flex-wrap items-center gap-1.5 py-1.5">
      {checkpoint.hasParquet ? (
        <button
          type="button"
          onClick={() => onDownload(checkpoint.table, 'parquet')}
          className="rounded-full px-2 py-0.5 text-xs font-medium transition-colors hover:bg-[#E0ECFA]"
          style={{ background: t.blue50, color: t.primary }}
        >
          Parquet
        </button>
      ) : (
        <span
          className="rounded-full px-2 py-0.5 text-xs"
          style={{ border: '1px dashed rgba(0,0,0,0.18)', color: t.textMuted }}
          title="Full Parquet snapshot has not been materialized yet"
        >
          Parquet pending
        </span>
      )}
      {checkpoint.hasCsv && (
        <button
          type="button"
          onClick={() => onDownload(checkpoint.table, 'csv')}
          className="rounded-full px-2 py-0.5 text-xs font-medium transition-colors hover:bg-[#EFEFEF]"
          style={{ background: t.subtleBg, color: t.textSecondary }}
          title="Legacy · used by Bit BI"
        >
          CSV
        </button>
      )}
    </span>
  );
}
