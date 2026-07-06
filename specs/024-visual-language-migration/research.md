# Research: Visual Language Migration (024)

**Date**: 2026-07-06 | **Branch**: `feature/024-visual-language-migration`
**Inputs**: full frontend inventory (all pages/widgets/primitives), new-language pattern extraction
(Delta Sync surfaces), Delta Sync data-wiring audit (code + live backend).

## 1. Decisions

### D1 — Token distribution: hybrid (Tailwind theme + CSS variables + `shared/ui/tokens.ts`)

**Decision**: three coordinated layers, one source of truth.

1. **`frontend/src/shared/ui/tokens.ts`** — promotion of `features/delta-sync/model/tokens.ts`
   (verbatim values). Exports `monitoringTokens`, `severityTokens`. Used for genuinely dynamic
   inline styles only (severity pill colors, bar gradient, letter-spacing values).
   `features/delta-sync/model/tokens.ts` becomes a re-export (kept one release for import
   stability, deleted at the end of this feature).
2. **shadcn CSS variables** (`shared/styles/index.css`) remapped to monitoring values:
   `--primary: #3C82D8` (HSL `213 66% 54%`), `--foreground: #2B2827`, `--muted-foreground: #736F6D`,
   `--border: rgba-equivalent hairline`, `--radius: 10px`, `--destructive` aligned to `#EF4444`/`#B91C1C`
   usage. This alone re-skins every primitive-consuming screen "centrally" (FR-002).
3. **Tailwind theme extension** (`tailwind.config.js`): semantic utilities so screens don't need
   inline styles for static cases — `boxShadow: { card, 'card-inner', 'icon-circle' }`,
   `colors: { ink: {DEFAULT:'#2B2827', secondary:'#736F6D', muted:'#A3A3A3', title:'#403C3B'}, brand: {DEFAULT:'#3C82D8', hover:'#3676C4', 50:'#EBF2FB', 100:'#E0ECFA'} }`,
   `borderColor: { hairline: 'rgba(0,0,0,0.12)', separator: 'rgba(0,0,0,0.06)' }`.

**Rationale**: The Delta Sync implementation applies tokens via inline `style={{}}` everywhere.
That was acceptable for 4 exemplar surfaces; for ~40 surfaces it defeats grep-ability, Tailwind
purging and consistency. CSS variables move the shadcn primitives wholesale; Tailwind utilities
replace the long tail of hex literals; `tokens.ts` remains for the dynamic remainder.

**Alternatives rejected**:
- *Inline styles everywhere (status quo of 023)* — unscalable, violates SC-004.
- *CSS variables only* — severity tokens are picked dynamically by key (`severityTokens[sev]`),
  and shadows/letter-spacing are awkward as ad-hoc var lookups in TSX; a TS module stays.

### D2 — Primitives first, screens second (bounded coexistence)

**Decision**: Phase A restyles tokens + all `shared/ui/ui/*` primitives (with tests) before any
screen migrates. Because 38 files import Button, 17 Badge, 14 Card, remapping CSS variables +
primitive classes instantly moves ~70% of the old surface *toward* the new language (palette,
radius, shadows) even before per-screen passes. Per-screen tasks then (a) replace raw
HTML/inline-Tailwind hotspots with primitives/utilities, (b) fix screen-specific layout
(uppercase headers → grid tables, dark pills → alpha pills, page bg).

**Rationale**: minimizes the two-language window (SC-006): after Phase A there is no screen that
still shows the *old dark-navy shadcn* look; the residual gap is per-screen idioms, burned down
one full surface per task.

**Alternative rejected**: screen-by-screen with local styles first — would fork the language a
third time (old, new-inline, new-primitive) and double test churn.

### D3 — Badge/pill variant mapping

New `badge.tsx` variant set (CVA), replacing dark variants:

| Old usage | New variant | Treatment |
|---|---|---|
| `default` (dark navy solid) | `info` | `bg #EBF2FB`, text `#3C82D8` |
| `secondary` | `neutral` | `bg #F5F5F4`, text `#736F6D` |
| `destructive` (solid red) | `critical` | `bg rgba(239,68,68,0.12)`, text `#B91C1C` |
| ad-hoc `bg-green-100 text-green-800` | `success` | `bg rgba(22,163,74,0.10)`, text `#15803D` |
| ad-hoc `bg-yellow/orange-*` | `warning` | `bg rgba(245,158,11,0.12)`, text `#B45309` |
| `outline` | `outline` | hairline border, text `#736F6D` |

Plus a `dot?: boolean` (6px `rounded-full` leading dot in `currentColor`-derived dot color) to
match `SyncHealthPill`/site-detail chips. Typography: `text-xs font-medium` (12px/500) — replaces
`text-xs font-semibold`. `AccountStatusBadge`, `PluginStatusBadge` and all inline
`bg-*-100 text-*-800` spans collapse onto these variants.

### D4 — Table strategy: keep `<table>` semantics, restyle; grid only where already grid

`shared/ui/ui/table.tsx` is restyled (headers `text-xs font-medium text-ink-secondary`, hairline
row borders `border-separator`, `hover:bg-[#FAFAFA]`, no uppercase/tracking) and the four
`<table>`-based surfaces (FileTable, UserListTable, AuditLogTable, GenerationListTable) adopt it.
We do **not** rewrite semantic tables to CSS-grid divs: the monitoring look is achievable on
`<table>` (the prototype's hairline/typography traits, not its DOM), and `<table>` keeps a11y
and TanStack Table integration. CSS-grid rows remain the pattern for list-style rows
(BatchListView rows, checkpoints) where Delta Sync already established it.

### D5 — Buttons: variants absorb hex literals

`button.tsx`: `default` → `#3C82D8`/hover `#3676C4` white text; `outline` → hairline border,
`text-ink`, `hover:bg-[#F5F5F4]`; `destructive` → keep solid `#EF4444`/hover `#DC2626` for
dialog confirmations, add `destructive-outline` (red hairline + `#B91C1C` text, `hover:bg-[#FEF2F2]`)
per `RebaselineCard.tsx:43`. Raw `<button className="bg-blue-600 …">` occurrences (BatchListView:339,
AccountsListPage:96, etc.) are replaced by the primitive.

### D6 — Page shell pattern

Old pages use `min-h-screen bg-gray-50` + `text-3xl font-bold text-gray-900` titles. Target (per
site-detail): page background stays subtle (`#FAFAFA`-family per prototype), titles
`text-[22px] font-medium` `#2B2827` with `-0.33px` tracking, sublines `text-sm` `#736F6D`.
A tiny shared `PageHeader` component (title + subline + optional actions) is introduced in
`shared/ui/` to stop each page re-implementing this.

### D7 — Tests

Per Development Policy: per-task TDD. For primitives — variant unit tests written first (assert
class/token output). For screens — update existing RTL assertions that pin old classes
(`bg-blue-600`, `variant="destructive"`), add smoke assertions on the new variant names where a
test already exists. No new snapshot suites; SC-001/SC-004 greps act as the mechanical
end-of-feature verification (encoded in `quickstart.md`).

### D8 — Clarifications handling

Spec has no `## Clarifications` session: this feature was planned in an autonomous session with
the product brief pre-answering scope (P1 Geist = yes; shell in scope — §8.2 restriction lifted;
P2 untouched; plan-first, implement after sign-off). Remaining open questions are listed in
`plan.md` § "Open questions for product review" instead of `/clarify`.

## 2. Old-language inventory (summary)

Full per-surface detail with file:line hotspots lives in **`migration-table.md`** (the main
deliverable table). Aggregates:

**Primitive blast radius** (import counts): Button 38 files, Badge 17, Card 14, Skeleton 10,
Alert 7, AlertDialog 7, Dialog 6, Label 6, Input 5, Table 4, Separator 4, Checkbox 4, Tabs 3,
Select 3.

**Old CSS variables** (`shared/styles/index.css:6-26`): dark-navy `--primary: 222.2 47.4% 11.2%`,
`--border: 214.3 31.8% 91.4%` (≈ gray-200), `--radius: 0.5rem`. Tailwind config
(`tailwind.config.js`): Geist already wired (line 22), radius mapped to `--radius` (lines 59-63).

**Recurring old idioms** (each a migration pattern, counted across `frontend/src`):
- `rounded-lg border border-gray-200 bg-white … shadow-sm` cards outside the Card primitive
  (chart widgets, PluginListView:28/39/51, UserListTable:214/225/232, AccountsListPage:127…)
- uppercase table headers `text-xs font-medium uppercase tracking-wider text-gray-500`
  (FileTable.tsx:177, UserListTable.tsx:241, AuditLogTable, GenerationListTable)
- raw `<select>`/`<input>`/`<button>` with `border-gray-300 … focus:ring-blue-500`
  (BatchListView:144-178, FileTable:152-158, AccountsListPage:96/113/135-147, SiteListItem:143,
  Pagination)
- inline conditional status spans `bg-green-100 text-green-800` / `bg-red-100 text-red-800`
  (AccountStatusBadge:28-36, PluginStatusBadge, BatchListView:275-286, PluginCard:61…)
- page scaffold `min-h-screen bg-gray-50` + `text-3xl font-bold text-gray-900`
  (DashboardPage:27-32, UploadHistoryPage:18-24, AccountsListPage:84-91, PluginsAdminPage:35-41,
  DeviceVerifyPage:166-174, SiteManagementPage:22-29, ComparisonListPage:40-44)
- raw tab bars `border-blue-500 text-blue-600` (PluginsAdminPage:48-80)
- spinner `animate-spin rounded-full border-4 border-gray-200 border-t-blue-600` (PluginListView:30)
- Header (`widgets/header/Header.tsx:24-100`): `border-b border-gray-200 bg-white shadow-sm`,
  `text-gray-700` nav links.

**Partially migrated**: `SiteListItem.tsx` mixes new v1/v2 chips (lines 123-130, hardcoded
`#EBF2FB/#3C82D8/#F5F5F4`) inside an old Card with dark Badges (103-115) and a raw retention
input (143). `SiteList.tsx` hosts the new `SyncHealthPill` next to old Alert/Skeleton states.

## 3. New-language pattern catalog (canonical exemplars)

Authoritative reference for every migration task ("Целевое представление" column):

| Pattern | Exemplar | Key implementation |
|---|---|---|
| Metric shell | `SyncStateShell.tsx:39-43` | `rounded-2xl p-2.5` bg `#EFEFEF`, inner white `rounded-xl` cards + `innerCardShadow` |
| Standard card | `CheckpointsCard.tsx:58`, `ActivityCard.tsx:39-43` | `rounded-[10px] bg-white p-4` + `cardShadow` |
| Severity/status pill | `SyncHealthPill.tsx:42-72`, `SyncStateShell.tsx:72-89` | `inline-flex gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium` + alpha bg + 6px dot |
| Chips (type/API) | `SiteDetailShell.tsx:52-86` | `rounded-full px-[9px] py-0.5 text-xs font-medium`, `#F5F5F4`/`#EBF2FB` |
| Typography scale | `SyncStateShell.tsx:94-104` (34px/-0.42px), `SiteDetailShell.tsx:52-54` (22px/-0.33px), 15px/-0.24px titles | weights 400/500 only; `tabular-nums` on all numbers |
| Grid table | `CheckpointsCard.tsx:131-171`, `DeltaBatchDetail.tsx:106-157` | grid `gridTemplateColumns`, `border-t` `rgba(0,0,0,0.06)`, `minHeight:40`, `hover:bg-[#FAFAFA]`, 12px/500 headers |
| Outline button | `CheckpointsCard.tsx:109-120` | `h-8 rounded-lg border px-3 text-sm font-medium`, hairline border, `hover:bg-[#F5F5F4]` |
| Destructive outline | `RebaselineCard.tsx:43-50` | red hairline `rgba(239,68,68,0.35)`, text `#B91C1C`, `hover:bg-[#FEF2F2]` |
| Primary action | `DeltaSyncDialogs.tsx:39` | `bg-[#3C82D8] hover:bg-[#3676C4]` |
| Segmented toggle | `CheckpointsCard.tsx` (Table\|Cards) | white active segment + `0 1px 2px rgba(0,0,0,0.08)` |
| Tabs | `SiteDetailShell.tsx:29-33, 88-107` | `rounded-lg px-4 py-[7px]`, active: `bg-[#f8f8f8]` + `#3C82D8` border/text |
| Hairlines | `ActivityCard.tsx:81-83`, `SyncStateShell.tsx:199` | 1px, `rgba(0,0,0,0.06)`; icon wells `rgba(24,22,22,0.08)` |
| Dialogs | `DeltaSyncDialogs.tsx:26-80` | shadcn AlertDialog as-is + colored action; red warning panel `rgba(239,68,68,0.08)/0.25` |
| Empty state | `DeltaSyncEmptyState.tsx:10-31` | centered card, icon in 44px `#EBF2FB` circle, 15px title + secondary text |
| Skeleton | `DeltaSyncWidget.tsx:80-86` | `animate-pulse rounded-* bg-gray-100` |
| Page shell | `SiteDetailShell.tsx:35-110` | 13px breadcrumb, 22px title, chips row, secondary subline, tabs `mt-[18px]` |

Known inconsistencies to normalize during Phase A/D (not copy): `DeltaBatchDetail.tsx` uses
`text-gray-900/border-gray-100` instead of tokens; `SiteDetailShell.tsx` hardcodes hexes instead
of importing tokens; new components bypass Card/Badge/Button primitives entirely (raw divs).
Design handoff README "Design Tokens" section + `tokens.ts` values match (verified).

## 4. Delta Sync data-wiring audit (product concern: "показывают картинку")

**Verdict: unfounded — every Delta Sync surface renders live backend data. Zero wiring defects.
No fixes enter this feature's scope (FR-010).**

### 4.1 Endpoint wiring (code audit)

All hooks in `features/delta-sync/api/` call real REST endpoints; 12/12 frontend paths have
exactly matching backend mappings:

| Hook | Endpoint (owner / admin) | Poll | Backend |
|---|---|---|---|
| `useDeltaSyncState` | `/v1/account/sites/{id}/delta/sync-state` / `/v1/sites/{id}/delta/sync-state` | 20s | `DeltaSyncUserController:79` / `DeltaSyncAdminController:81` |
| `useDeltaCheckpoints` | `…/delta/checkpoints` | 30s | `:109` / `:111` |
| `presignCheckpointDownload` | `…/delta/checkpoints/{table}/download?format=` | on click | `:142` / `:144` |
| `useDeltaSegments` (admin) | `/v1/sites/{id}/delta/segments?limit=20` | lazy on expand | `DeltaSyncAdminController:241` |
| `useRebuildCheckpoint` | `/v1/sites/{id}/delta/checkpoints/rebuild` | mutation | `:178` |
| `useRequestRebaseline` | `…/delta/rebaseline` | mutation | `:176` / `:208` |
| `useDeltaSyncHealth` | `/v1/account/sites/delta/health` / `/v1/accounts/{accountId}/sites/delta/health` | 30s | `DeltaSyncHealthController:55` / `:82` |

- Zod DTOs (`features/delta-sync/model/types.ts`) match backend response records field-for-field
  (`DeltaSyncStateResponseDto`, `DeltaCheckpointResponseDto`, `DeltaSyncHealthResponseDto`) — no
  casing or nullability mismatches.
- No MSW/mock layer in runtime: `app/main.tsx` has no service worker; `vite.config.ts` proxies
  `/api` → `http://localhost:8080`; all `vi.mock` usages are test-scoped.
- No hardcoded data: no `Math.random`/fixture arrays/static dates in delta-sync UI. Derived-only
  client-side computations: lag = `lastAppliedSeq - lastCheckpointSeq`; severity thresholds
  (`severity.ts`); stale pill = `updatedAt < now-24h`; checkpoint bar weights = `rowCount/maxRows`.

Per-element verdicts: lag track REAL; severity chip REAL(derived); sparkline REAL — client-side
accumulated series, one sample per completed sync-state poll (`useLagHistory.ts:20-32`, design
D6 of 023: no server-side history in MVP); throughput bars REAL (admin-only; owner sees empty
state pending **P2** — by design, do not "fix"); checkpoints table + file pills REAL; rebuild/
re-baseline chips REAL (persistent flags from V35); segments table REAL; sync-health pill REAL;
DeltaBatchDetail totals REAL (client-side sum of `batch.deltaStats`).

### 4.2 Live backend verification (2026-07-06)

Environment: `docker compose` postgres/localstack/redis up; backend `bootRun` on :8080
(dev profile, real Auth0 tenant `dev-dfm` per `local-dev/run-with-auth0.sh`).

1. **Endpoints registered**: live `/v3/api-docs` lists all 12 `**/delta/**` paths (sync-state,
   checkpoints, download, rebaseline, rebuild, segments, both health forms).
2. **Secured**: unauthenticated requests → HTTP 403 across all delta endpoints.
3. **Real data present**: DB holds 2 V2 sites; `site_sync_state` rows with live watermarks
   (e.g. site `139dc0a0…`: `last_applied_seq=879482`, `schema_version=3`, `updated_at`
   2026-07-05), 1+13 rows in `checkpoints`… 2+13 in `changelog_segments` — produced by the real
   gRPC ingestion path, not seeds.
4. **Not verified live** (requires interactive Auth0 browser login, out of session reach):
   an authenticated end-to-end poll cycle in the UI. Covered instead by (1)-(3) + path-exact code
   audit + contract tests. Optional manual step recorded in `quickstart.md`.

### 4.3 Reproduction steps

```bash
docker compose up -d postgres localstack redis
local-dev/run-with-auth0.sh                          # backend on :8080 (needs local-dev/auth0.env)
curl -s localhost:8080/v3/api-docs | jq '.paths | keys | map(select(contains("delta")))'
docker exec dfm-postgres psql -U dfm -d dfm -c "select * from site_sync_state;"
npm --prefix frontend run dev                        # login via Auth0, open /account/sites/{v2-site}
# Delta Sync tab: values must drift as the gRPC client streams (poll 20s/30s)
```
