/**
 * SiteDetailShell - presentational shell of the site-detail page (023, F3/F14).
 *
 * Pixel styles extracted from the design prototype (Delta Sync.dc.html), which is
 * the high-fidelity spec for this shell: breadcrumb "All sites" (13px, arrow 14px),
 * 22px/500 title with pill chips (type grey, API blue-50/blue or grey, Active
 * green 10%-alpha with 6px dot), 14px secondary subline, and pill tabs (7×16px,
 * r8; active = #f8f8f8 bg + #3C82D8 border/text). No Overview tab: "Upload
 * history" (default; the existing Batch List locked to this site) and "Delta Sync"
 * (rendered ONLY for clientApiVersion === 'V2' — absent from navigation for V1).
 */

import { ArrowLeft } from 'lucide-react';
import { severityTokens } from '@/shared/ui/tokens';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs';
import { BatchListWidget } from '@/widgets/upload-history/BatchListWidget';
import { DeltaSyncWidget } from '@/widgets/delta-sync/DeltaSyncWidget';
import type { Site } from '@/entities/site';

interface SiteDetailShellProps {
  site: Site;
  /** Admin capability flag: gates rebuild button + segments card inside Delta Sync. */
  canManage: boolean;
  /** True when the page is mounted under the admin route (switches API namespace). */
  admin?: boolean;
  /** Breadcrumb navigation back to the site list. */
  onBack: () => void;
}

export function SiteDetailShell({ site, canManage, admin = false, onBack }: SiteDetailShellProps) {
  const isV2 = site.clientApiVersion === 'V2';

  return (
    <div>
      {/* Breadcrumb */}
      <button
        type="button"
        onClick={onBack}
        className="inline-flex items-center gap-1.5 text-[13px] text-ink-secondary hover:text-ink transition-colors"
      >
        <ArrowLeft className="h-3.5 w-3.5" strokeWidth={1.5} />
        All sites
      </button>

      {/* Title + chips */}
      <div className="mt-2.5 flex flex-wrap items-center gap-3">
        <h1 className="text-[22px] font-medium leading-[1.1]" style={{ letterSpacing: '-0.33px' }}>
          {site.siteName}
        </h1>
        <span className="inline-flex items-center rounded-full bg-surface-subtle px-[9px] py-0.5 text-xs font-medium text-ink-secondary">
          {site.siteType}
        </span>
        {isV2 ? (
          <span className="inline-flex items-center rounded-full bg-brand-50 px-[9px] py-0.5 text-xs font-medium text-brand">
            Delta v2
          </span>
        ) : (
          <span className="inline-flex items-center rounded-full bg-surface-subtle px-[9px] py-0.5 text-xs font-medium text-ink-secondary">
            v1
          </span>
        )}
        {site.isActive ? (
          <span
            className="inline-flex items-center gap-1.5 rounded-full px-[9px] py-0.5 text-xs font-medium"
            style={{ background: severityTokens.healthy.bg, color: severityTokens.healthy.dot }}
          >
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: severityTokens.healthy.dot }} />
            Active
          </span>
        ) : (
          <span className="inline-flex items-center gap-1.5 rounded-full px-[9px] py-0.5 text-xs font-medium bg-separator text-ink-secondary">
            <span className="h-1.5 w-1.5 rounded-full bg-ink-muted" />
            Inactive
          </span>
        )}
      </div>

      {/* Subline (prototype copy per API version) */}
      <p className="mt-1.5 text-sm text-ink-secondary">
        {isV2
          ? 'Changelog stream over gRPC. Metrics refresh every 15 seconds.'
          : 'Full snapshot uploads over HTTP (v1).'}
      </p>

      {/* Tabs: Upload history (default) + Delta Sync (V2 only) */}
      <Tabs defaultValue="upload-history" className="mt-[18px]">
        <TabsList className="h-auto gap-1.5 bg-transparent p-0">
          <TabsTrigger value="upload-history">
            Upload history
          </TabsTrigger>
          {isV2 && (
            <TabsTrigger value="delta-sync">
              Delta Sync
            </TabsTrigger>
          )}
        </TabsList>
        <TabsContent value="upload-history" className="mt-5">
          <BatchListWidget siteId={site.id} />
        </TabsContent>
        {isV2 && (
          <TabsContent value="delta-sync" className="mt-5">
            <DeltaSyncWidget siteId={site.id} admin={admin} canManage={canManage} />
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}
