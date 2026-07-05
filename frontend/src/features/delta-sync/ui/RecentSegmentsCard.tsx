/**
 * RecentSegmentsCard — collapsible admin-only list of changelog segments (023, F8).
 *
 * Design handoff v2 §1d: clickable header (chevron rotates 180°), body grid
 * `1.2fr .8fr .9fr 1fr` = Seq range | Records | Mode | Created. Mode chips:
 * DELTA blue, CONTINUOUS green, FULL_SNAPSHOT amber. Dense cells use the
 * short date format (F1). Data comes from the shared segments query (already
 * fetched for the throughput bars), so expanding is instant.
 */

import { useState } from 'react';
import { ChevronDown, Layers } from 'lucide-react';
import { formatNumber, formatShortDate } from '@/shared/lib/formatters';
import type { DeltaSegment, DeltaSessionMode } from '../model/types';
import { monitoringTokens as t, severityTokens } from '../model/tokens';

const MODE_CHIP_STYLES: Record<DeltaSessionMode, { background: string; color: string }> = {
  DELTA: { background: t.blue50, color: t.primary },
  CONTINUOUS: { background: severityTokens.healthy.bg, color: severityTokens.healthy.text },
  FULL_SNAPSHOT: { background: severityTokens.elevated.bg, color: severityTokens.elevated.text },
};

interface RecentSegmentsCardProps {
  segments: DeltaSegment[];
  isLoading?: boolean;
}

export function RecentSegmentsCard({ segments, isLoading = false }: RecentSegmentsCardProps) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="rounded-[10px] bg-white" style={{ boxShadow: t.cardShadow }} data-testid="recent-segments-card">
      <button
        type="button"
        onClick={() => setExpanded((prev) => !prev)}
        aria-expanded={expanded}
        className="flex w-full items-center justify-between gap-3 p-4 text-left"
      >
        <span className="flex items-center gap-3">
          <span
            className="flex h-9 w-9 items-center justify-center rounded-full bg-white border"
            style={{ borderColor: t.iconWellBorder, boxShadow: t.iconCircleShadow }}
          >
            <Layers className="h-4 w-4" style={{ color: t.primary }} strokeWidth={1.5} />
          </span>
          <span>
            <span className="block text-[15px] font-medium" style={{ color: t.title, letterSpacing: '-0.24px' }}>
              Recent segments
            </span>
            <span className="block text-xs" style={{ color: t.textMuted }}>
              Last 20 changelog segments · admin only
            </span>
          </span>
        </span>
        <ChevronDown
          className="h-4 w-4 transition-transform duration-200"
          style={{ color: t.textSecondary, transform: expanded ? 'rotate(180deg)' : undefined }}
          strokeWidth={1.5}
        />
      </button>

      {expanded && (
        <div className="px-4 pb-4" data-testid="recent-segments-body">
          {isLoading ? (
            <div className="h-16 animate-pulse rounded-lg bg-gray-100" />
          ) : segments.length === 0 ? (
            <p className="text-sm" style={{ color: t.textMuted }}>
              No segments yet.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <div className="min-w-[480px]">
                <div
                  className="grid px-2 py-1.5 text-xs font-medium"
                  style={{ gridTemplateColumns: '1.2fr .8fr .9fr 1fr', color: t.textSecondary }}
                >
                  <span>Seq range</span>
                  <span className="text-right">Records</span>
                  <span>Mode</span>
                  <span>Created</span>
                </div>
                {segments.map((segment) => (
                  <div
                    key={`${segment.firstSeq}-${segment.lastSeq}`}
                    className="grid items-center border-t px-2 py-2 text-sm hover:bg-[#FAFAFA]"
                    style={{ gridTemplateColumns: '1.2fr .8fr .9fr 1fr', borderColor: t.separator, color: t.text }}
                  >
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {formatNumber(segment.firstSeq)}–{formatNumber(segment.lastSeq)}
                    </span>
                    <span className="text-right" style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {formatNumber(segment.recordCount)}
                    </span>
                    <span>
                      <span
                        className="rounded-full px-2 py-0.5 text-[11px] font-medium"
                        style={MODE_CHIP_STYLES[segment.mode]}
                      >
                        {segment.mode}
                      </span>
                    </span>
                    <span style={{ color: t.textSecondary, fontVariantNumeric: 'tabular-nums' }}>
                      {formatShortDate(segment.createdAt)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
