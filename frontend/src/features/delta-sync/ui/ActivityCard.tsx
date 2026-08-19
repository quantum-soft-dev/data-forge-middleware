/**
 * ActivityCard — Lag history sparkline + Segment throughput bars (023, F6).
 *
 * Design handoff v2 §1b: white shell card, grid `1fr 1px 1fr` with a hairline
 * divider. Throughput bars are ADMIN-ONLY while product decision P2 (owner
 * lite segments projection) is pending — owners get the lag history full
 * width (see ui-redesign-tasks.md status note).
 */

import { formatNumber } from '@/shared/lib/formatters';
import type { DeltaSegment } from '../model/types';
import { syncStatusTone, type SyncStatus } from '../model/severity';
import { monitoringTokens as t } from '@/shared/ui/tokens';
import { sparklinePoints } from '../model/useLagHistory';

const SPARK_WIDTH = 400;
const SPARK_HEIGHT = 56;
const THROUGHPUT_BARS = 16;

interface ActivityCardProps {
  /** Lag samples accumulated since page open, oldest first. */
  lagSamples: number[];
  /** The site's current sync verdict — colors the lag line (a pending first checkpoint is grey). */
  status: SyncStatus;
  /** Recent segments, newest first (admin data). */
  segments?: DeltaSegment[];
  /** False while P2 is unresolved for owners — hides the throughput panel. */
  showThroughput: boolean;
}

export function ActivityCard({ lagSamples, status, segments, showThroughput }: ActivityCardProps) {
  const sev = syncStatusTone(status);
  const points = sparklinePoints(lagSamples, SPARK_WIDTH, SPARK_HEIGHT);

  // Oldest → newest, left to right, capped at the last 16 segments.
  const bars = (segments ?? []).slice(0, THROUGHPUT_BARS).reverse();
  const maxRecords = Math.max(...bars.map((segment) => segment.recordCount), 1);

  return (
    <div
      className={`rounded-[10px] bg-white p-4 grid gap-4 ${
        showThroughput ? 'md:grid-cols-[1fr_1px_1fr]' : ''
      }`}
      style={{ boxShadow: t.cardShadow }}
      data-testid="activity-card"
    >
      {/* Lag history */}
      <div>
        <div className="text-sm font-medium" style={{ color: t.title }}>
          Lag history
        </div>
        <div className="text-xs" style={{ color: t.textMuted }}>
          Sampled on each poll · since page open
        </div>
        <svg
          viewBox={`0 0 ${SPARK_WIDTH} ${SPARK_HEIGHT}`}
          className="mt-3 w-full"
          height={SPARK_HEIGHT}
          preserveAspectRatio="none"
          data-testid="lag-sparkline"
        >
          {points && (
            <>
              <polygon
                points={`0,${SPARK_HEIGHT} ${points} ${SPARK_WIDTH},${SPARK_HEIGHT}`}
                fill={sev.dot}
                opacity={0.12}
              />
              <polyline
                points={points}
                fill="none"
                stroke={sev.dot}
                strokeWidth={1.5}
                strokeLinejoin="round"
                strokeLinecap="round"
              />
            </>
          )}
        </svg>
      </div>

      {showThroughput && (
        <>
          <div className="hidden md:block" style={{ background: t.separator }} />

          {/* Segment throughput */}
          <div>
            <div className="text-sm font-medium" style={{ color: t.title }}>
              Segment throughput
            </div>
            <div className="text-xs" style={{ color: t.textMuted }}>
              Records per changelog segment · last {THROUGHPUT_BARS} segments
            </div>
            {bars.length === 0 ? (
              <div className="mt-3 flex h-14 items-end text-xs" style={{ color: t.textMuted }}>
                No segments yet
              </div>
            ) : (
              <div className="mt-3 flex h-14 items-end gap-1.5" data-testid="throughput-bars">
                {bars.map((segment, index) => (
                  <div
                    // Index suffix: empty seals share firstSeq/lastSeq (lastSeq = firstSeq - 1).
                    key={`${segment.firstSeq}-${segment.lastSeq}-${index}`}
                    className="flex-1 rounded-[3px]"
                    style={{
                      height: `${Math.max((segment.recordCount / maxRecords) * 100, 4)}%`,
                      background: t.barGradient,
                    }}
                    title={`seq ${formatNumber(segment.firstSeq)}–${formatNumber(segment.lastSeq)} · ${formatNumber(segment.recordCount)} records`}
                  />
                ))}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
