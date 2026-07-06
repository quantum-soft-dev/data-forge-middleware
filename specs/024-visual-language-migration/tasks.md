# Tasks: Visual Language Migration — Unify Frontend on the Monitoring Design Language

**Input**: `specs/024-visual-language-migration/` — plan.md, migration-table.md (row ids referenced below), research.md (decisions D1-D8, pattern catalog §3), data-model.md (token/variant model), quickstart.md (gates & audits)
**Branch**: `feature/024-visual-language-migration`
**Execution discipline (Development Policy, CLAUDE.md)**: strictly **serial, WIP = 1** — no `[P]` markers by design, even where files don't overlap. For each task: (1) study the target row in migration-table.md + exemplar in research.md §3; (2) write/update the test set FIRST (red); (3) implement until green; (4) run the gate `npm --prefix frontend test` (100% green); (5) ONE atomic Conventional Commit referencing the task id. Do not start the next task before committing the current one.
**Status**: T001–T036 implemented on this branch (user go-ahead 2026-07-06); PR still waits on 022/023 merge and product visual sign-off (SC-005).

**Cross-cutting rules for every task**
- Visual-only: no route/API/polling/business-logic changes (FR-005); only class/variant/token assertions may change in tests.
- P2 boundary: never touch owner segments gating in `ActivityCard` (FR-009).
- Import tokens from `@/shared/ui/tokens`; never copy hex literals into components (SC-004).

---

## Phase A — Foundation: tokens & primitives (rows A1-A12)

- [x] **T001** [A1] Promote tokens to shared module
  Move `frontend/src/features/delta-sync/model/tokens.ts` → `frontend/src/shared/ui/tokens.ts` (values verbatim, incl. `SeverityToken`/`severityTokens`/`monitoringTokens`); make the old path a re-export; update all delta-sync imports to the new path.
  Tests first: move/adjust existing tokens tests; add test asserting old import path still re-exports (deleted later in T035).
  Commit: `refactor(ui): promote monitoring tokens to shared/ui (T001)`

- [x] **T002** [A2] Remap shadcn CSS variables to monitoring palette
  `frontend/src/shared/styles/index.css` light block per data-model.md §2 (`--primary`→`#3C82D8` HSL, `--foreground`→`#2B2827`, `--muted-foreground`→`#736F6D`, `--border/--input`→hairline (or `--border-hairline` companion if alpha blocked), `--ring`→brand, `--radius`→`10px`, destructive alignment). `.dark` untouched.
  Tests first: none unit-testable — this task's "test" is the full suite staying green + a manual full-app smoke pass (all routes render, no unreadable contrast); record findings in commit body.
  Commit: `feat(ui): remap CSS variables to monitoring palette (T002)`

- [x] **T003** [A3] Extend Tailwind theme with semantic monitoring utilities
  `frontend/tailwind.config.js`: `theme.extend` — `colors.ink{DEFAULT,secondary,muted,title}`, `colors.brand{DEFAULT,hover,50,100}`, `boxShadow{card,card-inner,icon-circle}`, `borderColor{hairline,separator}` per research D1/data-model §1.
  Tests first: a small utility test rendering a div with `shadow-card`/`text-ink-secondary` and asserting compiled class presence (or config unit test reading resolved theme).
  Commit: `feat(ui): add monitoring semantic utilities to tailwind theme (T003)`

- [x] **T004** [A4] Restyle Badge primitive → alpha pills + dot
  `frontend/src/shared/ui/ui/badge.tsx`: new CVA variants `info|neutral|success|warning|critical|stalled|outline` + `dot?: boolean` (6px dot) per research D3 / data-model §3; typography `text-xs font-medium`, `rounded-full px-2.5 py-1`, no focus ring. Keep old variant names as deprecated aliases mapping to new ones (`default`→`info`, `secondary`→`neutral`, `destructive`→`critical`) so 17 consumer files compile until their C-tasks.
  Tests first: variant/dot unit tests asserting classes + inline colors from severityTokens.
  Commit: `feat(ui): badge primitive to monitoring alpha pills (T004)`

- [x] **T005** [A5] Restyle Button primitive
  `frontend/src/shared/ui/ui/button.tsx` per research D5: `default` `#3C82D8`→`#3676C4`; `outline` hairline + `text-ink` + `hover:bg-[#F5F5F4]`; `ghost`; `destructive` solid `#EF4444`→`#DC2626`; new `destructive-outline` (`#B91C1C`, red hairline, `hover:bg-[#FEF2F2]`); `rounded-lg`; new size `compact` (h-8); focus-visible ring → brand color (a11y kept).
  Tests first: per-variant class assertions incl. focus-visible.
  Commit: `feat(ui): button primitive to monitoring variants (T005)`

- [x] **T006** [A6] Restyle Card primitive
  `frontend/src/shared/ui/ui/card.tsx`: `rounded-[10px] bg-white shadow-card` (drop border); `CardTitle` → `text-[15px] font-medium text-ink-title` tracking −0.24px; header/content paddings per `CheckpointsCard.tsx:58` exemplar.
  Tests first: update card tests; title typography assertion.
  Commit: `feat(ui): card primitive to monitoring card treatment (T006)`

- [x] **T007** [A7] Restyle Table primitive (keep `<table>` semantics — research D4)
  `frontend/src/shared/ui/ui/table.tsx`: `TableHead` → `text-xs font-medium text-ink-secondary` (no uppercase/tracking/h-12); `TableRow` → `border-separator` hairline + `hover:bg-[#FAFAFA]`; numeric-cell helper class with `tabular-nums`.
  Tests first: header/row class assertions.
  Commit: `feat(ui): table primitive to hairline monitoring style (T007)`

- [x] **T008** [A8] Tabs primitive: site-detail treatment as default
  `frontend/src/shared/ui/ui/tabs.tsx`: fold `TAB_TRIGGER_CLASSES` from `pages/site-detail/SiteDetailShell.tsx:29-33` into default trigger/list styles (transparent list, `rounded-lg px-4 py-[7px]`, active `bg-[#f8f8f8]` + brand border/text). Leave SiteDetailShell's local override in place (removed in T034/D1).
  Tests first: trigger state-class assertions (inactive/active/hover).
  Commit: `feat(ui): tabs primitive defaults to monitoring treatment (T008)`

- [x] **T009** [A9] Restyle form primitives: Input, Select, Checkbox, Label
  `frontend/src/shared/ui/ui/{input,select,checkbox,label}.tsx`: hairline borders, focus-visible `#3C82D8`, Select item hover `#F5F5F4`, `aria-invalid` error treatment (`#B91C1C` text/border) per spec Edge Case "Forms".
  Tests first: focus/error/hover class assertions incl. keyboard focus visibility.
  Commit: `feat(ui): form primitives to monitoring style (T009)`

- [x] **T010** [A10] Dialog & AlertDialog pass
  `frontend/src/shared/ui/ui/{dialog,alert-dialog}.tsx`: radius → r10, overlay check; action buttons now inherit T005 variants; document the red warning-panel pattern (from `DeltaSyncDialogs.tsx:60-70`) as a reusable snippet in the component JSDoc.
  Tests first: content radius + action variant inheritance assertions.
  Commit: `feat(ui): dialogs aligned to monitoring treatment (T010)`

- [x] **T011** [A11] Skeleton, Alert, Separator
  `frontend/src/shared/ui/ui/{skeleton,alert,separator}.tsx`: skeleton `animate-pulse bg-gray-100` (per `DeltaSyncWidget.tsx:80-86`); Alert → soft alpha panels (destructive = red panel per pattern catalog; default = neutral `#F5F5F4`), no hard borders; Separator hairline.
  Tests first: variant class assertions.
  Commit: `feat(ui): skeleton/alert/separator to monitoring style (T011)`

- [x] **T012** [A12] New shared PageHeader component
  Create `frontend/src/shared/ui/page-header.tsx` (`title`, `subtitle?`, `actions?`, `breadcrumb?`) rendering 22px/500/−0.33px title + `text-sm text-ink-secondary` subline per `SiteDetailShell.tsx:35-110`; export from shared ui index if present.
  Tests first: new RTL test file (render, typography classes, slots).
  Commit: `feat(ui): shared PageHeader component (T012)`

---

## Phase B — Global shell (row B1)

- [x] **T013** [B1] Migrate global Header/navigation
  `frontend/src/widgets/header/Header.tsx`: container `Header.tsx:24` → white bg + hairline bottom border (no shadow-sm); nav links `:41-100` → `text-ink-secondary hover:text-ink` 500, active route → brand accent echoing tabs treatment; logout → Button `ghost`; spacing per prototype shell. (Shell in scope — §8.2 lifted; layout IA unchanged per OQ-2 default.)
  Tests first: update header tests (link classes, active state), keep nav/logout behavior assertions unchanged.
  Commit: `feat(shell): header to monitoring visual language (T013)`

---

## Phase C — Surfaces (rows C1a-C8c; each task = one complete user-visible surface)

- [x] **T014** [C1a] Dashboard page scaffold
  `frontend/src/pages/dashboard/DashboardPage.tsx:27-32`: PageHeader (T012) replaces `text-3xl font-bold` block; page bg → neutral scaffold (plan D6); container metrics unchanged.
  Commit: `feat(dashboard): page scaffold to monitoring language (T014)`

- [x] **T015** [C1b] Dashboard chart widgets
  `frontend/src/widgets/dashboard-charts/{index,AreaChartWidget,BarChartWidget,PieChartWidget,TopCompaniesWidget}.tsx`: replace `rounded-lg border border-gray-200 …` wrappers with `<Card>`; titles → CardTitle 15px/500; Recharts chrome via token props (ticks `#A3A3A3` 11px, grid hairline, series brand/`barGradient` — pending OQ-1 sign-off). No data-logic changes.
  Commit: `feat(dashboard): chart widgets to monitoring cards and palette (T015)`

- [x] **T016** [C1c] Global errors widget + list
  `frontend/src/widgets/global-errors/GlobalErrorsWidget.tsx:51-59` + `frontend/src/features/global-errors/ui/{GlobalErrorList,GlobalErrorItem,GlobalErrorDetails}.tsx`: unread Badge → `critical` pill; list rows → hairline + `#FAFAFA` hover; severity indicators → severityTokens; empty state per `DeltaSyncEmptyState.tsx:10-31`; filter Buttons → T005 variants.
  Commit: `feat(global-errors): monitoring pills and hairline list (T016)`

- [x] **T017** [C2a] Site Management page scaffold
  `frontend/src/pages/site-management/SiteManagementPage.tsx:22-29`: PageHeader + scaffold; Separator already hairline via T011.
  Commit: `feat(site-mgmt): page scaffold to monitoring language (T017)`

- [x] **T018** [C2b] CreateSiteForm polish on new primitives
  `frontend/src/features/site-management/ui/CreateSiteForm*`: verify inherited T005/T009 styling; remove leftover local gray/navy classes; error text `#B91C1C`.
  Commit: `feat(site-mgmt): create-site form on monitoring primitives (T018)`

- [x] **T019** [C2c] SiteList states
  `frontend/src/widgets/site-list/SiteList.tsx:122-161`: skeleton/error/empty states via T011 primitives + empty-state pattern; SyncHealthPill untouched.
  Commit: `feat(site-list): list states to monitoring language (T019)`

- [x] **T020** [C2d] SiteListItem — finish the partial migration
  `frontend/src/widgets/site-list/ui/SiteListItem.tsx`: dark Badges `:103-115` → Badge `success`(+dot)/`neutral`; CDC outline `:117-121` + hardcoded chips `:123-130` → same chips as `SiteDetailShell.tsx:52-86` (extract tiny shared `SiteChips` into `frontend/src/entities/site/ui/` if dedupe is trivial); retention input `:143` → Input primitive; action Buttons `:175-205` → T005 variants (`outline`, `destructive-outline`); deactivate dialog action `:225` `bg-orange-500` → warning-styled action per data-model palette; Card → T006.
  Commit: `feat(site-list): site item fully on monitoring language (T020)`

- [x] **T021** [C3a] Upload History page scaffold
  `frontend/src/pages/upload-history/UploadHistoryPage.tsx:18-24` + `frontend/src/widgets/upload-history/BatchListWidget.tsx`: PageHeader + scaffold.
  Commit: `feat(upload-history): page scaffold to monitoring language (T021)`

- [x] **T022** [C3b] BatchListView (largest single-file migration)
  `frontend/src/features/upload-history/ui/BatchListView.tsx`: filter bar `:139` → hairline card row; raw `<select>`s `:144-178` → Select primitive; refresh `:200` + load-more `:339` + view-errors `:321` → Button variants; empty state `:211` → pattern; list `:241-266` → grid-row pattern (`CheckpointsCard.tsx:131-171`) with `#FAFAFA` hover; inline status badges `:275-286` → Badge severity variants (map: completed→success, in-progress→info, failed→critical, timeout→stalled); meta text → ink tokens, `tabular-nums` on counts/sizes.
  Commit: `feat(upload-history): batch list to monitoring language (T022)`

- [x] **T023** [C3c] FileTable
  `frontend/src/features/upload-history/ui/FileTable.tsx`: search input `:152-158` → Input primitive; `<table>` `:162-236` adopts T007 (kill uppercase `:177`, `bg-gray-50` head `:165`); selected rows `:213` → `#EBF2FB`; selection bar `:242` → info panel (brand-50); `tabular-nums` size/date columns.
  Commit: `feat(upload-history): file table to monitoring table style (T023)`

- [x] **T024** [C3d] BatchDetailView (v1) + ErrorListView
  `frontend/src/features/upload-history/ui/{BatchDetailView,ErrorListView}.tsx`: meta grid/cards → T006; status pills → Badge variants; error rows → hairline pattern with severityTokens; visually consistent with sibling `DeltaBatchDetail.tsx`.
  Commit: `feat(upload-history): v1 batch detail and errors to monitoring language (T024)`

- [x] **T025** [C4a] Comparisons list page + widget chrome
  `frontend/src/pages/comparison/ComparisonListPage.tsx:40-44` + `frontend/src/widgets/comparison/ComparisonListWidget.tsx:114-149`: PageHeader; container → Card; pagination footer → hairline + Button variants.
  Commit: `feat(comparison): list page chrome to monitoring language (T025)`

- [x] **T026** [C4b] Comparison list rows/cards
  `frontend/src/features/file-comparison/ui/ComparisonListView.tsx` + `frontend/src/entities/comparison/ui/ComparisonCard.tsx` (+ virtualized list): rows/cards → T006 + hairlines; statuses → Badge severity variants.
  Commit: `feat(comparison): list rows to monitoring language (T026)`

- [x] **T027** [C4c] Comparison detail + diff viewer chrome
  `frontend/src/pages/comparison/ComparisonDetailPage.tsx` + DiffViewerWidget chrome: PageHeader, panels → cards/hairlines; **diff highlight colors (green/red) unchanged**; verify readability.
  Commit: `feat(comparison): detail chrome to monitoring language (T027)`

- [x] **T028** [C5a] My Plugins page + widget tabs/filters
  `frontend/src/pages/account/plugins/MyPluginsPage.tsx` + `frontend/src/widgets/my-plugins/MyPluginsWidget.tsx` + `PluginTabFilters`: PageHeader; tabs → T008 primitive; filter selects/date/page-size → T009.
  Commit: `feat(my-plugins): page shell and filters to monitoring language (T028)`

- [x] **T029** [C5b] PluginCard
  `frontend/src/features/my-plugins/ui/PluginCard.tsx:43-106`: card → T006; icon block `:47-50` → icon well (hairline `iconWellBorder` + `shadow-icon-circle`); status `:59-61` → Badge `success`/`neutral`; deactivate `:105` → `destructive-outline`.
  Commit: `feat(my-plugins): plugin card to monitoring language (T029)`

- [x] **T030** [C5c] My Plugins logs & SQL tables
  Logs tab + SQL tab list components under `frontend/src/features/my-plugins/ui/`: tables → T007 hairline pattern; action-type/status → Badge severity variants; `tabular-nums` stats.
  Commit: `feat(my-plugins): logs and sql tables to monitoring language (T030)`

- [x] **T031** [C6] Admin plugins area (page+tabs, list view, audit/SQL tables, status badge)
  `frontend/src/pages/admin/plugins/PluginsAdminPage.tsx:35-80` (raw tab bar → T008; PageHeader), `frontend/src/features/plugin-admin/ui/PluginListView.tsx:28-99` (cards → T006, spinner → Skeleton, event pills → `neutral`, icon wells), `AuditLogTable.tsx` + SqlHistory `GenerationListTable` (→ T007 + severity pills), `frontend/src/entities/plugin/ui/PluginStatusBadge.tsx` (→ Badge variants). Four commits allowed if cleaner, else one per sub-surface order C6a→C6d; keep WIP=1 within.
  Commits: `feat(plugin-admin): … (T031a-d)` per migration-table rows C6a-C6d.

- [x] **T032** [C7] Admin accounts area
  Serial sub-tasks per rows C7a-C7e, one commit each: (a) `AccountsListPage.tsx:84-189` raw buttons/selects/panels → primitives + PageHeader; (b) `UserListTable.tsx:157-258` → T007 table, wrapper Card, icon actions → ghost/destructive-outline Buttons, dates `tabular-nums`; (c) `AccountStatusBadge.tsx:28-50` → Badge `success`/`critical`+dot; (d) `AccountDetailsPage.tsx` + cards/dialogs/action-log → T006/T004/T010; (e) account create/edit/search dialogs + `widgets/account-table/Pagination.tsx` → T009/T010/T005.
  Commits: `feat(admin-accounts): … (T032a-e)`.

- [x] **T033** [C8] Device-verify, login/fallback, admin sites & settings
  (a) `frontend/src/pages/device-verify/DeviceVerifyPage.tsx:166-325`: three flow states on new Cards; info box → brand-50 panel; success/error states per palette; code input mono + `tabular-nums`. (b) login + 404/fallback sweep. (c) `pages/admin/**` sites list & app settings (incl. batch-retention schedule form) → Table/form/PageHeader patterns.
  Commits: `feat(device-verify)… (T033a)`, `feat(misc-pages)… (T033b)`, `feat(admin-misc)… (T033c)`.

---

## Phase D — Convergence, cleanup, audit (rows D1-D3)

- [x] **T034** [D1] Delta Sync surfaces re-point to shared tokens (zero pixel drift)
  Update imports in `frontend/src/features/delta-sync/ui/*`, `widgets/delta-sync/*`, `pages/site-detail/*` to `@/shared/ui/tokens`; replace hardcoded hexes in `SiteDetailShell.tsx` with tokens/utilities; normalize `DeltaBatchDetail.tsx` off-spec grays (`text-gray-900`, `border-gray-100`) to tokens; drop SiteDetailShell local tab override in favor of T008 defaults.
  Tests first: existing delta-sync suites stay green unchanged except class-source assertions.
  Commit: `refactor(delta-sync): consume shared tokens, no visual changes (T034)`

- [x] **T035** [D2] Delete legacy
  Remove `frontend/src/features/delta-sync/model/tokens.ts` re-export (sweep imports), deprecated Badge variant aliases from T004, and any now-unused CVA variants/classes.
  Commit: `chore(ui): remove legacy token re-export and deprecated variants (T035)`

- [x] **T036** [D3] Mechanical audit + feature documentation
  Run quickstart.md audits (old-language grep → 0 hits; token-source grep → tokens.ts only); fix stragglers; execute manual visual checklist per route; write `docs/cr-visual-language-migration.md` (Rule 1) and update CLAUDE.md "Recent Changes".
  Commit: `docs: visual language migration CR, audit results, CLAUDE.md (T036)`

---

## Dependencies (summary)

`T001 → T002/T003 → T004…T012 (any order, serial) → T013 → T014…T033 (listed order) → T034 → T035 → T036`
Every C-task depends on the full Phase A; T034 additionally on T024 (C3d). No parallel execution — WIP=1 is policy, not a limitation of the graph.

## Definition of Done (feature)

All checkboxes above committed serially; SC-001…SC-006 verified (quickstart.md); PR into
`develop` (squash) after `./gradlew integrationTest` green; product visual sign-off recorded per
migration-table row.
