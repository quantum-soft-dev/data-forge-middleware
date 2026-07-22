# Handoff: Delta Sync — Sync Monitoring & Management (Delta Client v2, feature 022)

## Overview

Design concept + interactive prototype for the new **Delta Sync** surfaces in the Data Forge frontend, per `ui-requirements.md` (included in this bundle). It covers all three surfaces from the requirements:

1. **Delta Sync tab** on the site-detail page (§6) — sync state, lag visualization, checkpoints, admin actions.
2. **Delta Stats block** in Batch Detail (§5) — per-table insert/update/delete counts, "Table changes".
3. **Sync health badge** in the site list (§3.4).

This satisfies Definition of Done item 0 (design concept before implementation).

## About the Design Files

`prototype/Delta Sync.dc.html` is a **design reference built in HTML** — a self-contained prototype showing intended look and behavior. It is **not production code**. Your task is to **recreate it in the existing codebase**: React 19 + TypeScript + Tailwind CSS 3.4 + shadcn/ui, TanStack Query, Zod, FSD structure (`features/delta-sync/{api,model,ui}` + `widgets/delta-sync/DeltaSyncWidget.tsx` with a `canManage: boolean` prop), as mandated by requirements §8.2.

Open the prototype by serving the `prototype/` folder (e.g. `npx serve`) and opening `Delta Sync.dc.html` — it needs `support.js` and `assets/` next to it. Ignore the DC-runtime specifics (`<x-dc>`, `sc-if`, `sc-for`, `renderVals`) — they are prototype plumbing. The inline styles and the logic in the `Component` class are the spec.

Prototype-only chrome, NOT to implement: the top "Prototype · viewing as Owner/Admin" strip (in the real app the role comes from auth), the simulated drift/polling timer (real app: TanStack Query `refetchInterval` 15–30 s), and mock data.

## Fidelity

**High-fidelity.** Colors, typography, spacing, radii, and shadows are exact and should be recreated faithfully. Copy text is final unless product says otherwise.

## Design decisions (answers to the open questions of §8.3)

- **Operational palette = existing status hexes, new treatment.** Reuses green `#16A34A`, amber `#F59E0B`, red `#EF4444`, orange `#F97316` (already in the product's badge palette) but applies a distinct "monitoring" treatment: 6px dot + 12%-alpha pill background + darker full-color text (e.g. green text `#15803D`, amber `#B45309`, red `#B91C1C`, orange `#C2410C`). This treatment is reusable for future monitoring surfaces.
- **Lag is shown as distance, not a number**: a horizontal track with threshold zones and a moving pointer (see below), plus the big number.
- **Thresholds**: Healthy < 1,000 · Elevated 1,000–10,000 · Critical > 10,000 unmaterialized records. "Stalled" (no sync-state update > 24 h on an active site) is a separate orange state overriding the lag color.

## Screens / Views

Global: font **Geist** (400/500/600 only — never 700). Geist is not yet loaded in the product — the design position is to adopt it globally (self-hosted, separate mini-task); until product signs that off, all metrics in this spec are safe to apply on the system font stack. Dates/times follow the product's existing `formatDateTime` ("Jul 05, 2026 09:14"); short form "Jul 05, 12:41" for dense table cells, text color `#2B2827`, secondary `#736F6D`, muted `#A3A3A3`, page background `#FFFFFF`, primary blue `#3C82D8`, blue wash `#EBF2FB`. Cards: white, radius 10px, shadow `0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)`. Content column max-width 1120px, 16px gaps between cards. Numbers always `font-variant-numeric: tabular-nums`, formatted with thousands separators (`toLocaleString('en-US')` / existing `shared/lib/formatters`).

### 1. Delta Sync tab (`DeltaSyncWidget`, site detail)

Only rendered when `site.clientApiVersion === 'V2'` — for V1 the tab is absent from navigation entirely.

**Information architecture (new — the site-detail page does not exist yet).** First iteration of the site-detail shell: breadcrumb "All sites" (back to `/account/sites`) → title = site name + chips (type, API version "Delta v2"/"v1", Active) + one-line subline → tabs. **No Overview tab**: tabs are **Upload history** (default; the existing Batch List filtered by this site) and **Delta Sync** (only rendered when `clientApiVersion === 'V2'` — for V1 sites the shell shows Upload history alone). Admin reaches the same shell from the Admin panel; only `canManage` changes. The prototype demonstrates both the V2 shell (store-berlin-01) and the V1 shell (warehouse-legacy).

Vertical stack, 16px gap:

**a) Sync State shell** — the product's double-layer metric card: outer `#EFEFEF`, radius 16px, padding 10px, grid `1fr 340px`, gap 10px. Inner cards: white, radius 12px, shadow `0 1px 1.75px rgba(0,0,0,0.25), 0 0 0.5px rgba(0,0,0,0.04)`.

- **Lag card (left)**: header row = 36px icon circle (white, hairline border `rgba(24,22,22,0.08)`, activity icon in blue) + title "Sync lag" (15px/500) + sub "Records applied by the client but not yet checkpointed" (12px muted); right side = severity chip (dot + label Healthy/Elevated/Critical/Stalled). Headline: lag number 34px/500, tracking −0.42px, colored by severity, + "records behind checkpoint" 14px secondary.
  - **Lag track**: labels row above ("Last checkpoint · seq N" left, "Applied · seq N" right, 12px). Rail 10px tall, pill, background zones on a **square-root scale, max 20,000**: 0–22.4% green at 10% alpha, 22.4–70.7% amber, 70.7–100% red. Threshold ticks (1px, `rgba(0,0,0,0.18)`) at 22.4% ("1k · watch") and 70.7% ("10k · critical"), labels 11px muted below. Fill bar from 0 to `sqrt(lag/20000)*100`% in the severity color; pointer = 14px white circle with 3px severity border at the fill end. Fill/pointer animate with `transition: .6s ease` on poll updates.
- **Right column, two stacked cards**: "Last checkpoint" (label 13px/500, value "seq 47,890" 22px/500, clock icon + relative time 13px secondary; "Rebuild queued" blue chip appears after rebuild is scheduled) and "Schema version" (value "v 12", "View schema" blue 12px link to the existing Schema Viewer; divider; "Last activity" row: green pulsing 7px dot + "live · updated just now", OR orange "Sync stalled?" chip with alert-triangle icon when stale > 24 h).
- **Empty state** (no `site_sync_state` row yet — client never connected): the entire tab body is replaced by a single centered card — database icon in a 44px blue-50 circle, "No sync activity yet" 15px/500, sub "The Delta client for this site has not connected yet. Sync state, checkpoints and activity will appear after the first session." No sync-state shell, no Activity, no Checkpoints, no re-baseline card. Built in the prototype: scenario `empty`.

**b) Activity card** — white shell card, grid `1fr 1px 1fr` with a hairline divider:
- **Lag history**: title 14px/500 + sub "Sampled on each poll · since page open" (history accumulates client-side from page open — there is no server-side time series in MVP); SVG sparkline 56px tall, 1.5px line in severity color, area fill = same color at 12% alpha. Cold start (1–2 samples): render the line flat from the first sample — no placeholder text needed.
- **Segment throughput**: "Records per changelog segment · last 16 segments"; bar row, bars `flex:1`, gap 6px, radius 3px, `linear-gradient(180deg, #3C82D8, #C9DCF4)` (the product's only permitted gradient use), height proportional to record count. **Visible to owner too** — decision per feedback D2: backend exposes a "lite" segments projection for owners (`recordCount` + `createdAt` only, no seq ranges or S3 keys); the full segments table stays admin-only.

**c) Checkpoints card** — header: 36px icon well (table icon) + "Checkpoints" + sub "Materialized snapshot per table · download links valid 15 minutes"; right: **Table | Cards segmented toggle** (track `rgba(0,0,0,0.04)`, active segment white with small shadow) and, **admin only**, outline button "Rebuild checkpoint now" with refresh icon.
- **Table view** (default): grid columns `1.4fr .8fr 1fr 1fr 1fr` = Table | Seq (right) | Rows (right) | Last updated | Files. Rows 14px, ~40px tall, hairline top borders, hover `#FAFAFA`. Stale table (last update > 24 h): amber "stale" pill next to name, updated cell in `#B45309`. Files cell — **Parquet is the primary/target format, CSV is legacy**: (1) "Parquet" pill first, blue-50 bg `#EBF2FB` + blue text (full per-table Parquet load); (2) if the full Parquet snapshot is not yet materialized but CSV exists — a non-clickable dashed-border pill "Parquet pending" (border `1px dashed rgba(0,0,0,0.18)`, text `#A3A3A3`, tooltip "Full Parquet snapshot has not been materialized yet"); (3) "CSV" pill second, **muted** (`#F5F5F4` bg, `#736F6D` text, hover `#EFEFEF`), tooltip "Legacy · used by Bit BI" — it will disappear entirely after Bit BI migrates, and the row layout survives its absence without reflow (pills are a flex row with gap); (4) both keys null → em-dash. Do not mix in per-segment delta Parquet — that is a different entity (Recent segments, admin-only). Click requests a fresh presigned URL (15-min TTL, never cached longer). Beyond ~15 tables the table simply grows; add a client-side name filter input in the card header when a site exceeds that (no pagination).
- **Cards view** (alternative, kept per requirements exploration): 3-column grid of bordered cards (radius 10px): table name + stale pill, row count 22px/500 + "rows", **relative-weight bar** (4px track `#F5F5F4`, blue fill = rows/maxRows %), seq + updated meta row, Parquet pill / "Parquet pending" / muted legacy CSV pill (same rules as the table view, or "no files yet"). Recommendation: ship the **table** as default (scales to many tables, scannable numbers); keep cards as an optional toggle only if cheap — the weight bar is the one thing the table can't show.

**d) Recent segments (admin only)** — collapsible white card. Header row (clickable, chevron rotates 180°): layers icon well, "Recent segments", sub "Last 20 changelog segments · admin only". Body: grid `1.2fr .8fr .9fr 1fr` = Seq range | Records (right) | Mode | Created. Mode chips: DELTA blue (`#EBF2FB`/`#3C82D8`), CONTINUOUS green (10% alpha/`#15803D`), FULL_SNAPSHOT amber (12% alpha/`#B45309`).

**e) Full re-baseline card** — visible to owner AND admin. Title + description "The client will re-send a full snapshot on next connect. Use when the changelog and checkpoints have diverged." Right: destructive-outline button "Request full re-baseline" (white bg, border `rgba(239,68,68,0.35)`, text `#B91C1C`, hover `#FEF2F2`). After confirmation the button is replaced by an amber pill "Full snapshot scheduled on next connect", and the same amber chip appears in the Lag card header.

**Role gating (`canManage`)**: owner sees a/b/c(read-only, no rebuild)/e. Admin additionally sees the rebuild button and the segments card.

### 2. Batch Detail — Delta stats block

When `batch.deltaStats.length > 0`: hide the Files section, FileTable, and Download/Excel/Compare buttons. Show:
- **Meta card**: green check-circle in a 40px 10%-alpha circle, title "Batch #" + first 8 chars of the UUID (e.g. `Batch #d3f8e0f1`), 17px/500; chips "Completed" (green), "Delta session" (blue-50/blue) and a separate grey mode chip ("CONTINUOUS" / "DELTA" / "FULL_SNAPSHOT" — two chips avoid the tautological "Delta session · DELTA"); meta row Started / Completed / Seq range (13px, values dark + tabular, product `formatDateTime` format).
- **Table changes card**: sub-line "Delta sessions carry no files — changes are applied directly to each table." Grid `1.6fr 1fr 1fr 1fr 1fr` = Table | Inserted | Updated | Deleted | Total (all numbers right-aligned). Inserted green `#16A34A` with `+` prefix; Updated blue `#3B82F6`; Deleted red `#EF4444` with `−` (U+2212) prefix; Total 500 weight. **Total row** at bottom: 600 weight, stronger top border `rgba(0,0,0,0.12)`, sums per column. Sort rows by table name client-side (don't trust backend order).
- Both `deltaStats` and `files` empty → empty state "No changes in this session". Files present → unchanged v1 behavior.

### 3. Site list — health badge

The health pill and API chip join the **real** `SiteListItem` row (name + Active/Inactive badge + type badge + created/retention meta + Deactivate/Delete actions), not a simplified one. Placement: the API chip ("Delta v2" blue-50/blue, "v1" grey) sits in the badge cluster after the type badge; the health pill is right-aligned in the flexible space between the name block and the action buttons. On narrow widths the health pill wraps under the name block before the actions do. While bulk health data is loading: render nothing (no skeleton pill). For V2 sites the pill reads (dot + 12%-alpha bg + colored text, 12px/500):
- Healthy: "Synced · lag 12" (green)
- Elevated: "Lag 2.3k" (amber) — lag ≥ 1000 formatted as `(lag/1000).toFixed(1) + 'k'`
- Critical: "Lag 18.2k" (red)
- Stalled: "Stalled · 26 h" (orange)
- No sync-state row yet: grey pill "No sync yet".
- V1 sites: muted text "Snapshot uploads" instead of a badge.

## Interactions & Behavior

- **Polling**: TanStack Query `refetchInterval` 15–30 s on sync-state (and optionally checkpoints). Lag fill/pointer animate via CSS `transition .6s ease`. Live indicator: 7px green dot with a 2s expanding-ring pulse (`box-shadow` keyframes, see `dfPulse` in the prototype).
- **Rebuild checkpoint (admin)** → shadcn `AlertDialog`: title "Rebuild checkpoint now?", body "A checkpoint rebuild will be scheduled outside the regular schedule. This can be a heavy operation on large tables.", Cancel + primary blue "Rebuild now". On confirm: POST `.../delta/checkpoints/rebuild`, toast "Checkpoint rebuild scheduled", invalidate sync-state + checkpoints queries, show "Rebuild queued" chip.
- **Re-baseline (admin + owner)** → `AlertDialog` with a red warning panel (`rgba(239,68,68,0.08)` bg, `rgba(239,68,68,0.25)` border, alert-triangle icon): "The client will re-send a full snapshot on next connect. This may take a while for large datasets and cannot be cancelled once the client starts." Cancel + solid red "Request re-baseline". On confirm: POST `.../delta/rebaseline`, toast "Full re-baseline requested", swap button for pending pill. Never `window.confirm`.
- **Downloads**: each click requests a fresh presigned URL; toast "Download link generated · valid 15 minutes".
- **Toasts**: implement with the product's standard **Sonner** (default placement/style) — the bottom-center toast in the prototype is an illustration, not a spec. Mutation errors (4xx/5xx on rebuild/rebaseline): Sonner error toast with the product's standard error copy ("Something went wrong. Please try again."); no inline red block.
- **Chip lifecycles**: "Rebuild queued" shows while the backend `rebuildRequested` flag is set and is cleared by the server once the rebuild completes (visible on next poll). "Full snapshot scheduled on next connect" is driven by the persistent `rebaselineRequested` flag (migration V35); when the client actually starts its FULL_SNAPSHOT session the server clears the flag and the "Request full re-baseline" button returns.
- **Hover states**: rows `#FAFAFA`; outline buttons `#F5F5F4`; primary `#3676C4`; destructive-outline `#FEF2F2`; pills blue-50 → `#E0ECFA`. Transitions ~150ms, browser default easing. No bounce/stagger.
- **Loading**: skeleton cards (`animate-pulse`), consistent with `BatchDetailView`. **Error**: existing pattern `rounded-lg border border-red-200 bg-red-50`.

## State Management

- Queries: `deltaSyncState(siteId)` (poll 15–30 s), `deltaCheckpoints(siteId)`, `deltaSegments(siteId, limit 20)` (admin, fetch on expand). Zod DTOs per §7 endpoints (backend TODO — build these first).
- Local UI state: checkpoint view `'table' | 'cards'`, segments collapsed/expanded, dialog open state.
- Mutations: rebuild, rebaseline — both invalidate sync-state + checkpoints on success; rebaseline success also drives the "scheduled" pill (persist via a flag in the sync-state DTO if backend can expose it; otherwise local until refetch).
- Lag = `lastAppliedSeq − lastCheckpointSeq`, computed on the client; severity thresholds 1,000 / 10,000; stalled = `updatedAt` older than 24 h while site is active.

## Design Tokens

- Colors: primary `#3C82D8` (hover `#3676C4`), blue-50 `#EBF2FB`, blue-100 `#E0ECFA`; text `#2B2827` / `#736F6D` / `#A3A3A3` / titles `#403C3B`; subtle bg `#F5F5F4`, hover row `#FAFAFA`, metric shell `#EFEFEF`. Severity: green `#16A34A`/text `#15803D`, amber `#F59E0B`/`#B45309`, red `#EF4444`/`#B91C1C`, orange `#F97316`/`#C2410C`; chip bg = severity color at 10–12% alpha.
- Type: Geist; 34px lag headline (−0.42px), 22px values/H1 (−0.33px), 17px card titles, 15px section titles (−0.24px), 14px body, 13px meta, 12px chips/labels, 11px axis labels. Weights 400/500/600 only.
- Radii: 16px metric shell, 12px inner metric cards, 10px shell cards, 8px buttons/tabs, 6px small toggle segments, 9999px pills/chips/rails.
- Shadows: card `0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)`; inner card `0 1px 1.75px rgba(0,0,0,0.25), 0 0 0.5px rgba(0,0,0,0.04)`; icon circle `0 5px 4.375px rgba(0,0,0,0.01), 0 5px 6.125px rgba(0,0,0,0.05)`.
- Borders: `rgba(0,0,0,0.12)` default, `rgba(0,0,0,0.06)` separators, `rgba(24,22,22,0.08)` icon-well hairline.

## Assets & Icons

- `prototype/assets/data-forge-icon.svg` — existing product logo (from `frontend/public/`).
- Icons: **lucide-react** (already in the codebase): `Activity`, `Clock`, `Database`, `Table`, `Layers`, `RefreshCw`, `Download`, `Bell`, `ChevronDown`, `ChevronRight`, `ArrowLeft`, `CheckCircle2`, `AlertTriangle`. 1.5px stroke, sizes 14–20.

## Files

- `prototype/Delta Sync.dc.html` — the interactive prototype (all three surfaces; Owner/Admin toggle; scenarios healthy/watch/critical/stalled available as component props). Serve the folder and open in a browser.
- `prototype/support.js`, `prototype/assets/` — prototype runtime + logo (required next to the HTML).
- `ui-requirements.md` — the original requirements document (§ references above point into it).
