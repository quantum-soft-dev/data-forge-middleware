# Implementation Plan: Visual Language Migration — Unify Frontend on the Monitoring Design Language

**Branch**: `feature/024-visual-language-migration` (spec-kit id: `024-visual-language-migration`) | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `specs/024-visual-language-migration/spec.md`
**Status**: Plan complete — **awaiting product sign-off before implementation** (per session brief: план сначала, реализация после подтверждения)

## Summary

Eliminate the old shadcn-default visual language and unify the whole frontend on the
"monitoring" language established by Delta Sync (023). Approach: **(A)** promote
`features/delta-sync/model/tokens.ts` to `shared/ui/tokens.ts`, remap shadcn CSS variables and
extend the Tailwind theme with semantic monitoring utilities; **(B)** restyle every
`shared/ui/ui/*` primitive (badge → alpha pills, button → `#3C82D8`, card → r10+layered shadow,
table → hairline rows, tabs/inputs/dialogs/skeletons); **(C)** migrate surfaces screen-by-screen
in dependency order (header/shell → dashboard → sites → upload-history v1 → comparisons →
plugins → admin → device-verify); **(D)** re-point Delta Sync surfaces to the shared tokens,
delete legacy, run the mechanical audit. The product's data-wiring concern about Delta Sync was
audited and closed with zero defects (research.md §4) — no wiring work in scope.

## Technical Context

**Language/Version**: TypeScript 5.6, React 19.2 (frontend only; backend untouched)
**Primary Dependencies**: Vite 5.4, Tailwind CSS 3.4, shadcn/ui (CVA), Radix UI, TanStack Query v5, Recharts, @fontsource/geist-sans (P1=yes, already global)
**Storage**: N/A (no data changes)
**Testing**: Vitest + React Testing Library; per-task gate `npm --prefix frontend test`
**Target Platform**: Web (desktop-first, existing responsive breakpoints preserved)
**Project Type**: Web app — work confined to `frontend/src/**`, `frontend/tailwind.config.js`
**Performance Goals**: No regression; pure class/token changes (no runtime style computation added)
**Constraints**: Visual-only (FR-005); P2 untouched (FR-009); `.dark` block out of scope; no route/API/polling changes
**Scale/Scope**: ~40 surfaces / ~60 files; 12 primitives; reference = `design_handoff_delta_sync` (README Design Tokens + prototype inline styles)

## Constitution Check

*GATE: The spec-kit constitution is intentionally empty; the binding rules are CLAUDE.md
“Development Policy” (single source of dev rules). Compliance:*

- **Rule 1 (feature branch, PR to develop, docs)**: branch `feature/024-visual-language-migration`;
  ⚠️ **based on `feature/023-delta-sync-ui`** (which is based on 022; neither merged) because the
  exemplar components physically live there. PR lands only after 022 → 023 land (see Risks).
  Feature documentation: `docs/cr-visual-language-migration.md` (task in Phase D).
- **Rule 2 (TDD, WIP=1, atomic Conventional Commits, per-task gate)**: encoded per-row in
  `migration-table.md` and per-task in `tasks.md`; gate = `npm --prefix frontend test`
  (backend gate irrelevant — no backend files touched; pre-commit hook still runs it: keep green).
- **Gates**: per-task `npm --prefix frontend test`; before-PR `./gradlew integrationTest`
  (unchanged backend ⇒ expected green); merge gate = CI + review.

**Violations**: none. **Complexity Tracking**: empty.

## Project Structure

### Documentation (this feature)

```text
specs/024-visual-language-migration/
├── spec.md              # WHAT/WHY (product-facing)
├── plan.md              # this file
├── research.md          # decisions D1-D8, inventory summary, pattern catalog, data-wiring audit
├── migration-table.md   # ★ main deliverable: surface-by-surface migration matrix (A/B/C/D rows)
├── quickstart.md        # verification: audit greps, test gates, manual visual checklist
├── data-model.md        # token & variant model (design-token "entities")
└── tasks.md             # /tasks output: ordered TDD tasks mapped to table rows
```

(`contracts/` intentionally omitted — no API surface changes; FR-005.)

### Source Code (repository root)

```text
frontend/
├── tailwind.config.js                    # A3: theme.extend (ink/brand colors, card shadows, hairlines)
└── src/
    ├── shared/
    │   ├── styles/index.css              # A2: CSS variable remap (light block only)
    │   └── ui/
    │       ├── tokens.ts                 # A1: promoted from features/delta-sync/model/tokens.ts
    │       ├── page-header.tsx           # A12: new shared PageHeader
    │       └── ui/*.tsx                  # A4-A11: badge, button, card, table, tabs, input, select,
    │                                     #          checkbox, label, dialog, alert-dialog, skeleton,
    │                                     #          alert, separator
    ├── widgets/
    │   ├── header/Header.tsx             # B1
    │   ├── dashboard-charts/*            # C1b
    │   ├── global-errors/*               # C1c
    │   ├── site-list/*                   # C2c, C2d
    │   ├── upload-history/*              # C3a
    │   ├── comparison/*                  # C4a
    │   ├── my-plugins/*                  # C5a
    │   ├── plugin-admin/*                # C6b-C6c
    │   ├── user-management/*             # C7b
    │   └── account-table/Pagination.tsx  # C7e
    ├── features/
    │   ├── delta-sync/model/tokens.ts    # A1: re-export → deleted in D2
    │   ├── delta-sync/ui/*, upload-history/ui/DeltaBatchDetail.tsx   # D1: re-point to shared tokens
    │   ├── upload-history/ui/*           # C3b-C3d (BatchListView, FileTable, BatchDetailView, ErrorListView)
    │   ├── global-errors/ui/*            # C1c
    │   ├── file-comparison/ui/*          # C4b
    │   ├── my-plugins/ui/*               # C5b-C5c
    │   ├── plugin-admin/ui/*             # C6b-C6c
    │   └── account-create|edit|search/*  # C7e
    ├── entities/
    │   ├── account/ui/AccountStatusBadge.tsx   # C7c
    │   ├── plugin/ui/PluginStatusBadge.tsx     # C6d
    │   └── comparison/ui/ComparisonCard.tsx    # C4b
    └── pages/                            # C1a, C2a, C3a, C4a/C4c, C5a, C6a, C7a/C7d, C8a-C8c
```

**Structure Decision**: existing FSD layout unchanged; only two new files
(`shared/ui/tokens.ts`, `shared/ui/page-header.tsx`).

## Phased execution & coexistence strategy

Order is designed to keep the two-language window short and never ship a half-migrated screen (SC-006):

1. **Phase A (foundation, ~1 commit per primitive)** — tokens (A1), CSS vars (A2), Tailwind (A3),
   then primitives A4-A12. After A2 alone, every primitive-consuming screen already loses the
   dark-navy palette; after A4-A11 all primitives render the new language. Screens are visually
   "80% migrated" without being touched.
2. **Phase B (shell)** — Header B1: the one surface visible everywhere.
3. **Phase C (surfaces)** — per-area tasks in the order dashboard → sites → upload-history →
   comparisons → plugins → admin → device-verify/misc. Each task migrates a *complete*
   user-visible surface (page + its widgets/features) and updates its tests in the same commit.
   Order rationale: user-facing frequency first (dashboard/sites/upload-history), admin last.
4. **Phase D (convergence)** — Delta Sync re-points to shared tokens (D1), legacy deletion (D2),
   grep audit + `docs/cr-*.md` + CLAUDE.md update (D3).

Estimated size: Phase A ≈ 12 tasks (S/M), B ≈ 1 task (M), C ≈ 20 tasks (S:7 / M:11 / L:2),
D ≈ 3 tasks. Full matrix with hotspot line numbers: `migration-table.md`.

## Delta Sync data-wiring verification (product suspicion — closed)

Documented with evidence and repro steps in `research.md` §4. Bottom line: all Delta Sync
surfaces poll real REST endpoints (12/12 path-exact backend matches, Zod DTOs field-exact, no
runtime mocks; live backend + real DB rows verified 2026-07-06). **No wiring fixes needed or
planned.** Two by-design caveats communicated to product: the lag sparkline is a client-side
accumulated series (023 decision D6 — a server-side history would be a separate feature), and
owner throughput/segments stay hidden pending open decision **P2**.

## Open questions for product review (do not block plan approval)

1. **OQ-1 — Recharts palette**: exact series colors for dashboard charts (proposal: `brand`
   `#3C82D8` + `barGradient` for bars, severity hues where semantic). Sign-off wanted before C1b.
2. **OQ-2 — Header layout**: restyle-in-place (proposed, matches prototype shell) vs. any
   navigation IA changes (out of scope unless product says otherwise).
3. **OQ-3 — Destructive dialog actions**: keep solid red confirm buttons (proposed, per
   `DeltaSyncDialogs.tsx:74`) vs. outline treatment everywhere.
4. **OQ-4 — `.dark` block**: plan keeps it untouched (dead surface today). Confirm, or schedule
   deletion in D2.

## Risks & mitigations

- **Branch stacking (024 → 023 → 022, none merged)**: rebase burden and long-lived divergence.
  *Mitigation*: 024 starts implementation only after 022+023 merge to develop (also the product
  sign-off window); plan/spec docs are merge-independent.
- **CSS-variable remap (A2) has global blast radius** incl. surfaces scheduled late in C.
  *Mitigation*: A2 changes palette values only (no structure); intermediate state is "new colors
  on old layouts", which product accepted as the shortest coexistence path; full-app smoke check
  is part of the A2 task.
- **Tests pinned to old classes** fail en masse if primitives change first. *Mitigation*: A-tasks
  include updating the primitive test suites; C-tasks own their screens' assertions (TDD order).
- **Focus-ring/a11y regressions** from removing shadcn rings (A5/A9). *Mitigation*: replace, not
  remove — `#3C82D8` focus-visible outline is part of the A9 test set.
- **Recharts chrome** can't be styled via Tailwind. *Mitigation*: token-driven props (C1b), no
  chart-logic edits.

## Progress Tracking

- [x] Phase 0: research.md (decisions D1-D8; inventory; pattern catalog; wiring audit)
- [x] Phase 1: migration-table.md (main deliverable), data-model.md, quickstart.md; contracts/ N/A
- [x] Phase 2: tasks.md (via /tasks)
- [ ] Product sign-off on plan + OQ-1…OQ-4
- [ ] Implementation (blocked on sign-off and on 022/023 merge)
