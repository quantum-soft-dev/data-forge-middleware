# Migration Table: Visual Language Migration (024)

**The** authoritative surface-by-surface migration matrix. Each row = one surface/element.
Columns: current state (old-language hotspots, `file:line`), target (new language + canonical
exemplar), concrete changes, size (S <100 UI lines touched / M 100–300 / L >300), order &
dependencies. Exemplar references point to the pattern catalog in `research.md` §3 and to
`frontend/design_handoff_delta_sync/README.md` (Design Tokens) / prototype.

Phases: **A** foundation (tokens+primitives) → **B** shell → **C** surfaces → **D** convergence & audit.
All paths relative to `frontend/src/` unless noted.

## Phase A — Foundation: tokens & primitives

| # | Surface / element | Current state (old language) | Target (new language, exemplar) | What changes concretely | Size | Order / deps |
|---|---|---|---|---|---|---|
| A1 | Shared tokens module `shared/ui/tokens.ts` (new) | Tokens live in feature scope: `features/delta-sync/model/tokens.ts` (monitoringTokens, severityTokens); consumers import from feature | Single shared source (research D1) | Move file to `shared/ui/tokens.ts` verbatim; old path re-exports; update delta-sync imports | S | **1st** — everything depends on it |
| A2 | CSS variables `shared/styles/index.css:6-26` | Dark-navy shadcn defaults: `--primary: 222.2 47.4% 11.2%`, `--border: 214.3 31.8% 91.4%` (≈gray-200), `--ring` navy, `--radius: 0.5rem` | Monitoring palette (README Design Tokens): `--primary` `#3C82D8`, `--foreground` `#2B2827`, `--muted-foreground` `#736F6D`, hairline `--border`, `--radius: 10px` | Remap variable values (light block only; `.dark` untouched); verify focus ring & radius fallout across primitives | S | after A1 |
| A3 | Tailwind theme `tailwind.config.js:22-77` | Only Geist font + radius mapping; no monitoring colors/shadows — components hardcode hexes | Semantic utilities (research D1): `shadow-card/card-inner/icon-circle`, `ink.*`, `brand.*`, `border-hairline/separator` | Extend `theme.extend`; no removals (old utilities die with their usages in Phase C) | S | after A1 |
| A4 | `shared/ui/ui/badge.tsx` | Variants default/secondary/destructive/outline — solid dark bg, `font-semibold`, focus ring; used in 17 files | Alpha pills 12px/500 + optional 6px dot — `SyncHealthPill.tsx:42-72`, chips `SiteDetailShell.tsx:52-86` | New CVA variants per research D3 (`info/neutral/success/warning/critical/outline`) + `dot` prop; drop focus-ring styles; map old variant names or codemod call sites in C-tasks | M | after A2-A3; blocks all C |
| A5 | `shared/ui/ui/button.tsx` | Navy `default`, `bg-destructive`, `rounded-md`, ring focus; 38 consumer files | `#3C82D8`/`#3676C4` primary, hairline outline, destructive + destructive-outline — `CheckpointsCard.tsx:109-120`, `RebaselineCard.tsx:43-50`, `DeltaSyncDialogs.tsx:39` | Rework CVA variants per research D5; `rounded-lg`; `hover:bg-[#F5F5F4]` for outline/ghost; keep sizes, add `h-8` compact | M | after A2-A3; blocks all C |
| A6 | `shared/ui/ui/card.tsx` | `rounded-lg border bg-card shadow-sm`; `CardTitle` `text-2xl font-semibold` | White r10 + layered shadow, borderless — `CheckpointsCard.tsx:58` | Card: `rounded-[10px] shadow-card` (no border); CardTitle: `text-[15px] font-medium text-ink-title` tracking −0.24px; CardHeader/Content paddings per exemplar | S | after A2-A3 |
| A7 | `shared/ui/ui/table.tsx` | `TableHead h-12 … text-muted-foreground` (+ consumers add uppercase gray-500); `border-b` gray rows | Hairline rows, 12px/500 headers, `#FAFAFA` hover — `CheckpointsCard.tsx:131-171` (research D4: keep `<table>` DOM) | Restyle TableHead/Row/Cell classes; `tabular-nums` on numeric cell helper; no uppercase | S | after A2-A3 |
| A8 | `shared/ui/ui/tabs.tsx` | shadcn default boxed TabsList (muted bg, shadow on active) | Site-detail treatment as default — `SiteDetailShell.tsx:29-33,88-107` | Move `TAB_TRIGGER_CLASSES` into the primitive (default variant); SiteDetailShell drops its local override | S | after A2-A3 |
| A9 | `shared/ui/ui/input.tsx`, `select.tsx`, `checkbox.tsx`, `label.tsx` | `border-input` (gray-200), `focus-visible:ring-2` navy ring | Hairline border, `#3C82D8` focus, `#B91C1C` error text (spec Edge Cases: forms) | Restyle base classes; add `aria-invalid` error treatment; Select items hover `#F5F5F4` | M | after A2-A3 |
| A10 | `shared/ui/ui/dialog.tsx`, `alert-dialog.tsx` | shadcn defaults (already close); action buttons inherit old Button | Same DOM; colored actions — `DeltaSyncDialogs.tsx:26-80` | Inherit new Button variants (A5); overlay/radius audit to r10; warning-panel pattern documented for reuse | S | after A5 |
| A11 | `shared/ui/ui/skeleton.tsx`, `alert.tsx`, `separator.tsx` | `bg-muted` skeleton; Alert bordered boxes; Separator `bg-border` | `animate-pulse bg-gray-100` (`DeltaSyncWidget.tsx:80-86`); Alert → soft alpha panels (destructive = red panel `DeltaSyncDialogs.tsx:60-70`); Separator hairline | Restyle three primitives | S | after A2-A3 |
| A12 | Shared `PageHeader` (new, `shared/ui/page-header.tsx`) | Each page hand-rolls `text-3xl font-bold text-gray-900` + subtitle | 22px/500/−0.33px title + secondary subline (+ actions slot) — `SiteDetailShell.tsx:35-110` | New small component + tests; consumed by every C-phase page | S | after A3; blocks C pages |

## Phase B — Global shell

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| B1 | `widgets/header/Header.tsx` | `Header.tsx:24` `border-b border-gray-200 bg-white shadow-sm`; links `:41-100` `text-gray-700 hover:text-gray-900`; logout Button old variant | Hairline bottom border, `ink`/`ink-secondary` links, `#3C82D8` active state echoing tab treatment (`SiteDetailShell` tabs); Geist 500 | Restyle container + nav link classes incl. active-route accent; adopt new Button ghost for logout; height/spacing per prototype shell | M | after Phase A (in scope — §8.2 restriction lifted) |
| B2 | Page scaffold (all pages) | `min-h-screen bg-gray-50` + `mx-auto max-w-7xl px-4 py-8` repeated per page | Neutral page bg per prototype (`#FAFAFA`-family), same container metrics | Applied within each C-task via PageHeader/A12 + a documented scaffold class string (no new layout component needed) | — | folded into C rows |

## Phase C — Surfaces

### C1. Dashboard

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C1a | `pages/dashboard/DashboardPage.tsx` | `:27` bg-gray-50 page, `:31-32` `text-3xl font-bold text-gray-900` | PageHeader + neutral bg | Swap title block for PageHeader (A12); scaffold classes | S | after A12, B1 |
| C1b | `widgets/dashboard-charts/*` (Area/Bar/Pie/TopCompanies + index) | Each widget wraps in `rounded-lg border border-gray-200 bg-white p-6 shadow-sm`; titles `text-lg font-semibold text-gray-900`; Recharts default palette | Card A6 + 15px/500 titles; chart chrome in monitoring colors (axis `#A3A3A3` 11px, grid hairline, series `brand`/`barGradient` — `ActivityCard.tsx` sparkline/bars) | Replace wrappers with `<Card>`; retitle; set Recharts `stroke`/`fill`/tick props from tokens; **no data-logic changes** (spec Edge Case) | M | after A6 |
| C1c | `widgets/global-errors/GlobalErrorsWidget.tsx` + `features/global-errors/ui/*` (list, item, details) | `:55-59` Badge `destructive` unread count; `GlobalErrorList.tsx:95,107` gray empty state, `bg-gray-50` header row; old Buttons/Checkbox | `critical` alpha pill (A4); hairline list rows + `#FAFAFA` hover (grid-table pattern); empty state per `DeltaSyncEmptyState.tsx:10-31` | Swap Badge variant; restyle list rows/severity indicators to severity tokens; empty/loading states | M | after A4-A7 |

### C2. Site Management

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C2a | `pages/site-management/SiteManagementPage.tsx` | `:22-29` bg-gray-50, `text-3xl font-bold` | PageHeader + scaffold | As C1a | S | after A12 |
| C2b | `features/site-management/ui/CreateSiteForm.*` | Old Input/Label/Button, gray borders, navy focus | New form primitives (A9), primary Button (A5) | Re-verify after primitive restyle; fix leftover local classes; error text `#B91C1C` | S | after A5, A9 |
| C2c | `widgets/site-list/SiteList.tsx` | `:124-129` Skeletons, `:135,151` Alert destructive/empty | New Skeleton/Alert (A11); empty state pattern | Restyle states; keep SyncHealthPill as-is | S | after A11 |
| C2d | `widgets/site-list/ui/SiteListItem.tsx` — **partially migrated, must be finished** | `:91-93` old Card; `:103-115` dark Badges Active/Inactive; `:117-121` outline Badge CDC; `:123-130` hardcoded new-palette chips; `:143` raw retention input; `:175-205` old Buttons; `:225` `bg-orange-500` dialog action | Chips row exactly per `SiteDetailShell.tsx:52-86` (same site, same chips!); card A6; status pill with dot (A4) | Replace all badges with A4 variants (+dot for Active); dedupe chip markup with site-detail (extract tiny `SiteChips` if trivial); retention input → Input primitive; dialog action → warning-styled Button; remove hardcoded hexes → tokens | M | after A4-A6, A9 |

### C3. Upload History (v1 path)

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C3a | `pages/upload-history/UploadHistoryPage.tsx` + `widgets/upload-history/BatchListWidget.tsx` | `:18-24` bg-gray-50 + `text-3xl font-bold` | PageHeader + scaffold | As C1a | S | after A12 |
| C3b | `features/upload-history/ui/BatchListView.tsx` | `:139` gray filter bar; `:144-178` raw `<select>`s navy-focus; `:200` raw refresh button; `:211` gray empty state; `:241-266` bordered list, status icons; `:275-286` inline `bg-*-100 text-*-800` badges; `:321` raw errors button; `:339` `bg-blue-600` load-more | Filter bar as hairline card row; Select primitive (A9); status pills via A4 severity variants; grid-row list with `#FAFAFA` hover (`CheckpointsCard.tsx:131-171`); Buttons A5 | Largest single-file migration: replace raw controls with primitives, restyle rows to grid-table pattern, map batch statuses → severity pill variants | L | after A4-A9 |
| C3c | `features/upload-history/ui/FileTable.tsx` | `:152-158` raw search input; `:162-236` bordered `<table>`, `:165` `bg-gray-50` sticky head, `:177` uppercase gray-500 headers; `:213` `bg-blue-50` selected; `:242` blue selection bar | Table primitive restyled (A7, research D4); search → Input (A9); selected row `#EBF2FB`; selection bar as info panel | Adopt Table primitive classes; kill uppercase; `tabular-nums` on size/date columns | M | after A7, A9 |
| C3d | `features/upload-history/ui/BatchDetailView.tsx` + `ErrorListView.tsx` | Old cards/labels/badges throughout (v1 batch detail path); error rows gray-bordered | Match the already-migrated `DeltaBatchDetail.tsx:106-157` sibling (same page context) | Restyle meta grid, status pills (A4), error list rows to hairline pattern; severity → severityTokens | M | after A4-A7; align with C3b |

### C4. Comparisons

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C4a | `pages/comparison/ComparisonListPage.tsx` + `widgets/comparison/ComparisonListWidget.tsx` | `:40-44` old title block; widget `:148-149` `border-t` pagination footer muted text | PageHeader; hairline footer; Buttons A5 | Scaffold + footer restyle; list container to Card A6 | S | after A5, A6, A12 |
| C4b | `features/file-comparison/ui/ComparisonListView.tsx` + `entities/comparison/ui/ComparisonCard.tsx` (+ virtualized variant) | Old card borders, inline status colors | Card A6 + status pills A4; grid-row list | Restyle rows/cards, statuses → severity variants | M | after A4, A6 |
| C4c | `pages/comparison/ComparisonDetailPage.tsx` + DiffViewerWidget chrome | Old page scaffold, gray panels around diff | PageHeader; diff *chrome* (headers, legend, panels) to cards/hairlines; **diff highlight colors unchanged** (green/red semantics stay) | Restyle chrome only; verify diff readability | M | after A6, A12 |

### C5. Plugins — user-facing

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C5a | `pages/account/plugins/MyPluginsPage.tsx` + `widgets/my-plugins/MyPluginsWidget.tsx` (tabs: Logs/SQL + PluginTabFilters) | Old page scaffold; widget tabs and filter selects old-style | PageHeader; Tabs A8; Select/Input A9 | Scaffold; tab bar via primitive; filters restyle | M | after A8, A9, A12 |
| C5b | `features/my-plugins/ui/PluginCard.tsx` | `:43` gray-bordered card; `:47-50` `bg-green-50`/gray icon bg; `:59-61` dark Badge + hardcoded `bg-green-100 text-green-800`; `:105-106` red-tinted outline Button | Card A6; icon well w/ hairline (`iconWellBorder` + `iconCircleShadow`, `CheckpointsCard` icon circles); `success`/`neutral` pills; destructive-outline Button | Replace card/badges/buttons with primitives; icon well tokens | M | after A4-A6 |
| C5c | Plugin logs/SQL tables in My Plugins (log list, batch SQL status list) | Bordered tables, uppercase headers, inline status colors | Table A7 pattern + severity pills | Restyle to hairline tables; statuses → A4 | M | after A4, A7 |

### C6. Admin — plugins

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C6a | `pages/admin/plugins/PluginsAdminPage.tsx` | `:35-41` bg-gray-50 + `text-3xl`; `:48-80` raw tab bar `border-blue-500 text-blue-600` | PageHeader; Tabs A8 | Replace raw tabs with primitive; scaffold | M | after A8, A12 |
| C6b | `features/plugin-admin/ui/PluginListView.tsx` | `:28-39` gray cards + spinner `border-t-blue-600`; `:51` card hover shadow; `:55-56` blue icon box; `:82-96` gray event pills; `:99` bordered action row | Cards A6; skeleton A11 (kill spinner); `neutral` pills; icon well tokens | Restyle grid cards, pills, loading states | M | after A4, A6, A11 |
| C6c | `features/plugin-admin/ui/AuditLogTable.tsx` + SqlHistory `GenerationListTable` (+ widgets wrappers) | Bordered `<table>`, uppercase gray-500 headers, inline status colors | Table A7; action-type/status → severity pills; `tabular-nums` on counts | Adopt restyled Table; map statuses | M | after A4, A7 |
| C6d | `entities/plugin/ui/PluginStatusBadge.tsx` | Inline conditional `bg-*-100 text-*-800` spans | Badge A4 (`success`/`neutral`) | Replace with primitive; delete local classes | S | after A4 |

### C7. Admin — accounts/users

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C7a | `pages/accounts/users/AccountsListPage.tsx` | `:84-91` scaffold; `:96` raw `bg-blue-600` Create button; `:113` raw Filters button; `:127-147` gray filter panel + raw selects; `:189` red error card | PageHeader; Buttons A5; filter panel as Card A6 + Select A9; error → Alert A11 | Replace raw controls with primitives; scaffold | M | after A5, A6, A9, A12 |
| C7b | `widgets/user-management/UserListTable.tsx` | `:214-232` gray loading/empty/wrapper cards; `:234-241` `<table>` uppercase headers; `:256-258` row hover/cells; `:157-193` inline icon-buttons `text-*-600 hover:bg-*-50` | Table A7; wrapper Card A6; icon actions → ghost Buttons (A5) with hairline hover; empty state pattern | Adopt Table primitive; actions to Button `ghost`/`destructive-outline`; `tabular-nums` dates | L | after A5-A7 |
| C7c | `entities/account/ui/AccountStatusBadge.tsx` | `:28-50` inline `bg-green-100 text-green-800` / `bg-red-100 text-red-800` spans | Badge A4 `success`/`critical` (+dot) | Replace spans with primitive | S | after A4 |
| C7d | `pages/accounts/details/AccountDetailsPage.tsx` (+ AccountCard, action buttons, AdminActionLogList) | Old cards, dark badges, old dialogs | Cards A6, pills A4, dialogs A10 | Restyle detail cards, statuses, confirm dialogs | M | after A4-A6, A10 |
| C7e | Account dialogs: `features/account-create/*`, `features/account-edit/*`, `features/account-search/*`, `widgets/account-table/Pagination.tsx` | Dialog + old form primitives; raw pagination select/buttons | Dialog A10 + form A9; pagination via Button/Select primitives, `tabular-nums` page info | Re-verify post-primitives; replace raw pagination controls | M | after A9, A10 |

### C8. Device verify & misc

| # | Surface | Current state | Target (exemplar) | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| C8a | `pages/device-verify/DeviceVerifyPage.tsx` | `:166-174` scaffold + `text-3xl font-bold`; `:181-221` Cards; `:203` `text-red-500` errors; `:235` `bg-blue-50 border-blue-200` info box; `:325` `border-green-200` success Card | Cards A6; info box → `#EBF2FB` panel (blue-50 token); success state w/ `success` pill; error `#B91C1C` | Restyle three flow states on new primitives; code input keeps mono formatting + `tabular-nums` | M | after A6, A9 |
| C8b | Login page + any 404/fallback routes | Old scaffold/buttons (verify during implementation) | PageHeader/Button/Card | Sweep + restyle | S | after A5, A12 |
| C8c | Admin sites & settings pages (`pages/admin/**`: sites list, app settings incl. batch-retention schedule form) | Old tables/forms/scaffold (same idioms as C7) | Table A7, form A9, PageHeader | Sweep + restyle per patterns above | M | after A7, A9, A12 |

## Phase D — Convergence, cleanup, audit

| # | Surface | Current state | Target | What changes | Size | Order / deps |
|---|---|---|---|---|---|---|
| D1 | Delta Sync surfaces re-point to shared tokens | Import from `features/delta-sync/model/tokens.ts`; `SiteDetailShell.tsx` hardcodes hexes; `DeltaBatchDetail.tsx` uses `text-gray-900/border-gray-100` off-spec | Same pixels, shared source (FR-006) | Update imports to `shared/ui/tokens`; replace hardcoded hexes with tokens/utilities; normalize DeltaBatchDetail colors to tokens; adopt Tabs A8 default (drop local override) | M | after A1-A8, C3d |
| D2 | Delete legacy | Old token file re-export; any dead variant styles; obsolete test fixtures | No duplicates (FR-001) | Delete `features/delta-sync/model/tokens.ts` re-export after import sweep; remove unused CVA variants | S | after D1, all C |
| D3 | Mechanical audit + docs | — | SC-001/SC-004 green | Run quickstart grep audit → fix stragglers; write `docs/cr-visual-language-migration.md` (Rule 1: feature must be documented); update CLAUDE.md Recent Changes | S | last |

## Cross-cutting rules for every C-row

- TDD per Development Policy: update/write test expectations first; per-task gate `npm --prefix frontend test`.
- One row ≈ one atomic Conventional Commit (`feat(ui): …`, `feat(dashboard): …`) unless the row explicitly bundles page+widget.
- No behavior changes: assertions about handlers/queries in existing tests must not change (only class/variant assertions may).
- **P2 boundary**: nothing in `ActivityCard` segments/owner gating may change (FR-009).
