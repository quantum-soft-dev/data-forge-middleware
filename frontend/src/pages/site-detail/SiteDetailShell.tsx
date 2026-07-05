/**
 * SiteDetailShell - presentational shell of the site-detail page (023, F3).
 *
 * Layout per design handoff v2 (D4): breadcrumb "All sites" → title (site name +
 * type / API / Active chips) + one-line subline → tabs. No Overview tab: "Upload
 * history" (default; the existing Batch List locked to this site) and "Delta Sync"
 * (rendered ONLY for clientApiVersion === 'V2' — absent from navigation for V1).
 */

import { ArrowLeft, CheckCircle2, XCircle } from 'lucide-react';
import { Badge } from '@/shared/ui/ui/badge';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs';
import { BatchListWidget } from '@/widgets/upload-history/BatchListWidget';
import { DeltaSyncWidget } from '@/widgets/delta-sync/DeltaSyncWidget';
import type { Site } from '@/entities/site';
import { formatDate } from '@/shared/lib/formatters';

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
    <div className="space-y-4">
      {/* Breadcrumb */}
      <button
        type="button"
        onClick={onBack}
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
      >
        <ArrowLeft className="h-4 w-4" />
        All sites
      </button>

      {/* Title + chips + subline */}
      <div>
        <div className="flex items-center gap-2 flex-wrap">
          <h1 className="text-2xl font-semibold tracking-tight">{site.siteName}</h1>
          <Badge
            variant={site.siteType === 'POSTGRES_CDC' ? 'outline' : 'secondary'}
            className={site.siteType === 'POSTGRES_CDC' ? 'border-blue-400 text-blue-600' : ''}
          >
            {site.siteType === 'POSTGRES_CDC' ? 'Postgres CDC' : 'DBF'}
          </Badge>
          {isV2 ? (
            <Badge className="border-transparent bg-[#EBF2FB] text-[#3C82D8] hover:bg-[#E0ECFA]">Delta v2</Badge>
          ) : (
            <Badge className="border-transparent bg-[#F5F5F4] text-[#736F6D] hover:bg-[#EFEFEF]">v1</Badge>
          )}
          <Badge variant={site.isActive ? 'default' : 'secondary'}>
            {site.isActive ? (
              <>
                <CheckCircle2 className="mr-1 h-3 w-3" />
                Active
              </>
            ) : (
              <>
                <XCircle className="mr-1 h-3 w-3" />
                Inactive
              </>
            )}
          </Badge>
        </div>
        <p className="text-sm text-muted-foreground mt-1">
          {site.name} · Created {formatDate(site.createdAt)}
        </p>
      </div>

      {/* Tabs: Upload history (default) + Delta Sync (V2 only) */}
      <Tabs defaultValue="upload-history">
        <TabsList>
          <TabsTrigger value="upload-history">Upload history</TabsTrigger>
          {isV2 && <TabsTrigger value="delta-sync">Delta Sync</TabsTrigger>}
        </TabsList>
        <TabsContent value="upload-history" className="mt-4">
          <BatchListWidget siteId={site.id} />
        </TabsContent>
        {isV2 && (
          <TabsContent value="delta-sync" className="mt-4">
            <DeltaSyncWidget siteId={site.id} admin={admin} canManage={canManage} />
          </TabsContent>
        )}
      </Tabs>
    </div>
  );
}
