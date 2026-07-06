/**
 * DeltaBatchDetail — redesigned Batch Detail surfaces for Delta v2 sessions (023, F10).
 *
 * Design handoff v2 §2: meta card (status icon in a 10%-alpha circle,
 * "Batch #" + 8-char id, Completed + "Delta session" + grey mode chips,
 * Started / Completed / Seq range meta row) and the "Table changes" card
 * (grid 1.6fr 1fr×4, +green / blue / −red / bold Total, Total row, client-side
 * sort by table name). Delta sessions carry no files — the file UI never renders.
 */

import { CheckCircle2, Loader2, XCircle } from 'lucide-react';
import type { BatchDetail } from '@/entities/batch/model/types';
import { formatDateTime, formatNumber } from '@/shared/lib/formatters';

const GRID = '1.6fr 1fr 1fr 1fr 1fr';
const MINUS = '−';

interface DeltaBatchDetailProps {
  batch: BatchDetail;
  siteName?: string;
}

export function DeltaBatchDetail({ batch, siteName }: DeltaBatchDetailProps) {
  const stats = [...(batch.deltaStats ?? [])].sort((a, b) => a.table.localeCompare(b.table));
  const totals = stats.reduce(
    (acc, stat) => ({
      inserts: acc.inserts + stat.inserts,
      updates: acc.updates + stat.updates,
      deletes: acc.deletes + stat.deletes,
    }),
    { inserts: 0, updates: 0, deletes: 0 },
  );
  const grandTotal = totals.inserts + totals.updates + totals.deletes;

  const completed = batch.status === 'COMPLETED' || batch.status === 'COMPLETED_WITH_WARNINGS';
  const inProgress = batch.status === 'IN_PROGRESS';

  return (
    <div className="space-y-4" data-testid="delta-batch-detail">
      {/* Meta card */}
      <div className="rounded-[10px] bg-white p-5 shadow-card">
        <div className="flex flex-wrap items-center gap-3">
          <div
            className="flex h-10 w-10 items-center justify-center rounded-full"
            style={{
              background: completed
                ? 'rgba(22,163,74,0.10)'
                : inProgress
                  ? 'rgba(60,130,216,0.10)'
                  : 'rgba(239,68,68,0.10)',
            }}
          >
            {completed ? (
              <CheckCircle2 className="h-5 w-5 text-green-600" strokeWidth={1.5} />
            ) : inProgress ? (
              <Loader2 className="h-5 w-5 animate-spin text-blue-500" strokeWidth={1.5} />
            ) : (
              <XCircle className="h-5 w-5 text-red-500" strokeWidth={1.5} />
            )}
          </div>
          <h2 className="text-[17px] font-medium tracking-[-0.24px] text-ink">
            Batch #{batch.id.slice(0, 8)}
          </h2>
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
              completed
                ? 'bg-green-100 text-green-800'
                : inProgress
                  ? 'bg-blue-100 text-blue-800'
                  : 'bg-red-100 text-red-800'
            }`}
          >
            {batch.status === 'COMPLETED_WITH_WARNINGS' ? 'Completed (Warnings)' : titleCase(batch.status)}
          </span>
          <span className="rounded-full bg-brand-50 px-2.5 py-0.5 text-xs font-medium text-brand">
            Delta session
          </span>
          {batch.mode && (
            <span className="rounded-full bg-surface-subtle px-2.5 py-0.5 text-xs font-medium text-ink-secondary">
              {batch.mode}
            </span>
          )}
        </div>

        <div className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-[13px]">
          {siteName && <MetaItem label="Site" value={siteName} />}
          <MetaItem label="Started" value={formatDateTime(batch.startedAt)} />
          {batch.completedAt && <MetaItem label="Completed" value={formatDateTime(batch.completedAt)} />}
          {batch.seqRange && (
            <MetaItem
              label="Seq range"
              value={`${formatNumber(batch.seqRange.first)} – ${formatNumber(batch.seqRange.last)}`}
            />
          )}
        </div>
      </div>

      {/* Table changes card */}
      <div className="rounded-[10px] bg-white p-5 shadow-card">
        <h3 className="text-[15px] font-medium tracking-[-0.24px] text-ink-title">Table changes</h3>
        <p className="mt-0.5 text-xs text-ink-secondary">
          Delta sessions carry no files — changes are applied directly to each table.
        </p>

        <div className="mt-3 overflow-x-auto">
          <div className="min-w-[560px]">
            <div
              className="grid px-2 py-1.5 text-xs font-medium text-ink-secondary"
              style={{ gridTemplateColumns: GRID }}
            >
              <span>Table</span>
              <span className="text-right">Inserted</span>
              <span className="text-right">Updated</span>
              <span className="text-right">Deleted</span>
              <span className="text-right">Total</span>
            </div>
            {stats.map((stat) => (
              <div
                key={stat.table}
                className="grid items-center border-t border-separator px-2 py-2 text-sm hover:bg-surface-hover"
                style={{ gridTemplateColumns: GRID, fontVariantNumeric: 'tabular-nums' }}
                data-testid={`delta-stats-row-${stat.table}`}
              >
                <span className="font-medium text-ink">{stat.table}</span>
                <span className="text-right text-[#16A34A]">
                  {stat.inserts > 0 ? `+${formatNumber(stat.inserts)}` : formatNumber(stat.inserts)}
                </span>
                <span className="text-right text-[#3B82F6]">{formatNumber(stat.updates)}</span>
                <span className="text-right text-[#EF4444]">
                  {stat.deletes > 0 ? `${MINUS}${formatNumber(stat.deletes)}` : formatNumber(stat.deletes)}
                </span>
                <span className="text-right font-medium text-ink">
                  {formatNumber(stat.inserts + stat.updates + stat.deletes)}
                </span>
              </div>
            ))}
            {/* Total row */}
            <div
              className="grid items-center px-2 py-2 text-sm font-medium text-ink"
              style={{
                gridTemplateColumns: GRID,
                borderTop: '1px solid rgba(0,0,0,0.12)',
                fontVariantNumeric: 'tabular-nums',
              }}
              data-testid="delta-stats-total-row"
            >
              <span>Total</span>
              <span className="text-right text-[#16A34A]">
                {totals.inserts > 0 ? `+${formatNumber(totals.inserts)}` : formatNumber(totals.inserts)}
              </span>
              <span className="text-right text-[#3B82F6]">{formatNumber(totals.updates)}</span>
              <span className="text-right text-[#EF4444]">
                {totals.deletes > 0 ? `${MINUS}${formatNumber(totals.deletes)}` : formatNumber(totals.deletes)}
              </span>
              <span className="text-right">{formatNumber(grandTotal)}</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

function MetaItem({ label, value }: { label: string; value: string }) {
  return (
    <span className="text-ink-secondary">
      {label}{' '}
      <span className="font-medium text-ink" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </span>
    </span>
  );
}

function titleCase(status: string): string {
  return status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ');
}
