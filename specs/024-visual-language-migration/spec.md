# Feature Specification: Visual Language Migration — Unify Frontend on the Monitoring Design Language

**Feature Branch**: `feature/024-visual-language-migration` (spec-kit id: `024-visual-language-migration`)
**Created**: 2026-07-06
**Status**: Draft — awaiting product sign-off (plan-only stage; implementation starts after approval)
**Input**: User description: "Unify the entire frontend on the new 'monitoring' visual language currently present only on Delta Sync surfaces; migrate all remaining pages, widgets and shared primitives; global header/shell is now in scope; P2 (owner lite segments) must not be touched; visual-only — no behavior or data changes."

## Context

Feature 023 (Delta Sync UI) introduced a new "monitoring" visual language, specified in
`frontend/design_handoff_delta_sync/README.md` (Design Tokens) and the interactive prototype
`frontend/design_handoff_delta_sync/prototype/Delta Sync.dc.html`. It is fully applied on:
`pages/site-detail/*`, `features/delta-sync/*`, `widgets/delta-sync/*`,
`features/upload-history/ui/DeltaBatchDetail.tsx`, and partially on `widgets/site-list/*`.

Everything else still uses the original shadcn-default language (dark navy `--primary`,
`border-gray-200` cards, bordered tables with uppercase gray headers, `bg-blue-600` buttons,
dark Badge variants). The application currently ships **two visual languages at once**; product
has decided to eliminate the old one entirely.

Key traits of the target language (current single source: `features/delta-sync/model/tokens.ts`,
to be promoted to a shared location):

- **Font**: Geist 400/500 (600 reserved; already global via `@fontsource/geist-sans`, decision P1=yes, 2026-07-06)
- **Text**: `#2B2827` main / `#736F6D` secondary / `#A3A3A3` muted / `#403C3B` titles; negative letter-spacing on large sizes; `tabular-nums` on all numerics
- **Pills**: `rounded-full`, 12px/500, 10–12% alpha backgrounds + darker full-color text (+ optional 6px dot) — replacing dark Badge variants
- **Cards**: white, radius 10px, shadow `0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)` — replacing `border-gray-200` + `shadow-sm`
- **Primary**: `#3C82D8`, hover `#3676C4`
- **Hairlines**: `rgba(0,0,0,0.12)` borders / `rgba(0,0,0,0.06)` separators
- **Tables**: CSS-grid rows with hairline top-borders and `hover:bg-[#FAFAFA]` — replacing bordered `<table>` with uppercase `text-gray-500` headers

## User Scenarios & Testing *(mandatory)*

### User Story 1 - One coherent product look (Priority: P1)

A user navigating from the Dashboard to Site Management to a Site Detail page experiences one
consistent visual language: same typography, same pill/status treatment, same card and table
styling everywhere. No screen looks like "a different product".

**Why this priority**: This is the entire point of the feature — the current split-brain look
undermines product credibility, and every week of coexistence adds new old-language code.

**Independent Test**: Open every route (dashboard, sites, site detail, upload history, batch
detail, comparisons, my-plugins, admin: accounts/sites/plugins/settings, device-verify) and
verify against the design-token checklist (SC-001…SC-005); grep-based audit finds zero
old-language patterns.

**Acceptance Scenarios**:

1. **Given** any page in the app, **When** it renders a status (site active, batch status, plugin state, error severity), **Then** it is displayed as a rounded-full alpha pill per the monitoring token spec — never as a dark solid Badge.
2. **Given** any page in the app, **When** it renders a card/panel, **Then** the card is white r10 with the layered soft shadow — never `border border-gray-200 shadow-sm`.
3. **Given** any data table, **When** it renders headers and rows, **Then** headers are 12px/500 secondary-color (not uppercase gray-500) and rows are separated by hairlines with `#FAFAFA` hover.
4. **Given** any primary action button, **When** rendered, **Then** it uses `#3C82D8`/hover `#3676C4` (not `bg-blue-600`/dark navy `--primary`).

---

### User Story 2 - Shared primitives as the single styling source (Priority: P1)

A developer building a new screen gets the monitoring language "for free" by using shared
primitives (`Badge`, `Button`, `Card`, `Table`, `Tabs`, `Input`, `Select`, `Skeleton`, dialogs)
and shared tokens — without copying inline hex values from Delta Sync components.

**Why this priority**: Without centralization the migration cannot stick — the Delta Sync
implementation itself bypasses shared primitives with raw divs + inline styles, which does not
scale to ~40 surfaces and would freeze the codebase in copy-paste.

**Independent Test**: Restyled primitives render the monitoring language in isolation
(unit-tested variants); a screen composed only of primitives is indistinguishable in tokens
from the Delta Sync reference surfaces.

**Acceptance Scenarios**:

1. **Given** the shared token module, **When** any component needs a monitoring color/shadow/hairline, **Then** it imports it from the shared location (`shared/ui/tokens` + CSS variables), not from `features/delta-sync/model/tokens.ts` and not as a copied hex literal.
2. **Given** `shared/ui/ui/badge.tsx`, **When** used with the new variants, **Then** it renders alpha-background pills (incl. dot support) matching `SyncHealthPill` visuals.
3. **Given** existing screens that consume primitives (38 files import Button, 17 Badge, 14 Card), **When** primitives are restyled, **Then** those screens pick up the new look with at most variant-name adjustments.

---

### User Story 3 - Global shell matches the language (Priority: P2)

The global header/navigation (visible on every page) uses monitoring typography, hairline
borders and the new active/hover treatment, so the frame of the app matches its content.
(Product explicitly lifted the previous "don't touch the shell" restriction, ТЗ 023 §8.2.)

**Why this priority**: Highest-visibility single surface; but it depends on primitives/tokens
landing first, and the app remains usable if it lands a task later.

**Independent Test**: Header renders with new tokens on all routes, active-link and hover states
match the site-detail tab treatment; no `border-gray-200`/`text-gray-700` remains in `widgets/header/`.

**Acceptance Scenarios**:

1. **Given** any authenticated page, **When** the header renders, **Then** it uses a hairline bottom border, `#2B2827`/`#736F6D` link palette and `#3C82D8` active accents.

---

### User Story 4 - Product confidence in Delta Sync data wiring (Priority: P3, verification-only)

Product suspected the new Delta Sync surfaces "are not connected to data and show a picture".
This feature documents the audit result and carries **no wiring fixes** (none are needed).

**Why this priority**: Already resolved by audit during planning; recorded here so the concern
is closed with evidence, not assertion.

**Independent Test**: See `research.md` § "Delta Sync data-wiring audit" — reproducible against
a live backend.

**Acceptance Scenarios**:

1. **Given** a live backend with V2 sites, **When** the Delta Sync tab is open, **Then** sync-state/checkpoints/health values change as backend data changes (poll intervals 20s/30s/30s).

### Edge Cases

- **Recharts widgets** (dashboard-charts): chart internals (axis text, tooltip, palette) are styled via Recharts props, not Tailwind — migration must restyle chart chrome (card, title, axis/tooltip colors) without touching chart data logic.
- **Destructive flows** (site delete/deactivate, account lock/delete, plugin deactivate, re-baseline): keep the red destructive treatment while adopting the monitoring destructive palette (`#B91C1C` text / `rgba(239,68,68,…)` accents) — danger affordance must not weaken.
- **Forms & validation states** (device-verify, account dialogs, CreateSiteForm): error/focus states must be redefined on Input/Select/Label primitives (hairline + `#3C82D8` focus, `#B91C1C` errors) — the old `focus:ring-blue-500` rings disappear.
- **Legacy `.dark` block** in `index.css`: dark mode is not a supported product surface today — the `.dark` variable block is out of scope (left as-is) unless product says otherwise.
- **Partially-migrated SiteListItem**: already mixes languages (new v1/v2 chips inside an old Card) — must be finished, not half-kept.
- **Tests asserting old classes**: existing component tests that assert `bg-blue-600`, badge variants etc. must be updated task-by-task (TDD: update expectations first, then restyle).
- **P2 boundary**: `ActivityCard` owner empty-state ("No segments yet") and admin-only segments/throughput stay exactly as-is; migration must not alter that logic.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A single shared token source MUST exist (promotion of `features/delta-sync/model/tokens.ts` to `shared/ui/tokens.ts` + mapped CSS variables in `shared/styles/index.css`), and all monitoring colors/shadows/hairlines MUST be consumed from it. `features/delta-sync/model/tokens.ts` re-exports from the shared module during transition (no dead duplicate at the end).
- **FR-002**: Tailwind theme (`tailwind.config.js`) and shadcn CSS variables (`--primary`, `--border`, `--muted-foreground`, `--radius`, etc.) MUST be remapped to monitoring values so that primitive-consuming screens shift palette centrally.
- **FR-003**: Shared primitives in `shared/ui/ui/` MUST be restyled to the monitoring language: `badge` (alpha pills + dot), `button` (primary `#3C82D8`, outline hairline, destructive `#B91C1C`), `card` (r10 + layered shadow), `table` (hairline rows, non-uppercase 12px/500 headers), `tabs` (site-detail treatment as default), `input`/`select`/`checkbox`/`label` (hairline + new focus), `dialog`/`alert-dialog` (action colors), `skeleton`, `alert`, `separator`.
- **FR-004**: Every old-language surface MUST be migrated per the Migration Table (`migration-table.md`): Header/shell; Dashboard (page + 4 chart widgets + GlobalErrorsWidget/list); Site Management (page, CreateSiteForm, SiteList, SiteListItem); Upload History v1 (page, BatchListWidget, BatchListView, BatchDetailView, FileTable, ErrorListView); Comparisons (list + detail + diff viewer chrome); Plugins user (MyPluginsPage/Widget, PluginCard, tabs/filters) and admin (PluginsAdminPage, PluginListView, AuditLogTable, SQL history); Admin accounts (AccountsListPage, UserListTable, AccountDetailsPage, dialogs); device-verify; entity badges (`AccountStatusBadge`, `PluginStatusBadge`); pagination.
- **FR-005**: Migration MUST be visual-only: no route, API, polling, data-shape, or business-logic changes; DOM structure changes only where required by styling (e.g. table→grid rows).
- **FR-006**: The Delta Sync reference surfaces MUST be refactored to consume the shared tokens where trivially possible (import-path change; no pixel changes), so one language has one implementation.
- **FR-007**: Old-language patterns MUST be mechanically detectable as absent at the end: an audit checklist (grep for `border-gray-200`, `bg-blue-600`, `uppercase tracking-wider`, page-level `bg-gray-50`, `text-gray-900/700/600/500`, dark Badge usages) MUST return zero hits in `frontend/src`.
- **FR-008**: Each task MUST keep the frontend test suite green (`npm --prefix frontend test`), updating test expectations in the same task as the restyle (TDD order: expectations first).
- **FR-009**: The open product decision **P2 (owner lite segments)** MUST NOT be implemented, changed, or worked around by this feature.
- **FR-010**: The completed Delta Sync data-wiring audit MUST be recorded in `research.md` with reproduction steps; no wiring changes ship in this feature.

### Key Entities

- **Design token**: named visual constant (color/shadow/radius/hairline/typography rule); lives once in `shared/ui/tokens.ts` + CSS variables; consumed by primitives and screens.
- **Surface**: a page/widget/primitive listed in the Migration Table with current-state hotspots, target reference, change list, size (S/M/L) and dependency order.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Grep audit (FR-007 pattern list) over `frontend/src` returns **0 hits** outside `design_handoff_delta_sync/`.
- **SC-002**: 100% of surfaces in the Migration Table are marked migrated; each references its exemplar (file:line in Delta Sync surfaces or prototype section).
- **SC-003**: `npm --prefix frontend test` is green after every task (per-task gate) and at feature end; no test is skipped to pass.
- **SC-004**: One token source: `git grep` for monitoring hex literals (`#3C82D8`, `#2B2827`, `#736F6D`, card shadow string) in `frontend/src` matches only the shared token module (+ CSS variable definitions), not component files.
- **SC-005**: Side-by-side visual review of each migrated screen against `design_handoff_delta_sync` tokens/prototype passes product sign-off (tracked per Migration Table row).
- **SC-006**: The two-language window is bounded: tokens + primitives land first (Phase A), and every subsequent merged task leaves an entire user-visible surface fully in the new language (no screen ships half-migrated).

## Out of Scope

- P2 — owner lite segments (open product decision; untouched).
- Dark mode (`.dark` CSS block) — retained as-is, not redesigned.
- Any backend change; any data/API change; feature 022/023 functional changes.
- Redesigning page information architecture (layout/content stays; only visual language changes).
- New Figma/prototype work — `design_handoff_delta_sync` is the sole reference.
