# CR: Visual Language Migration — Unified Monitoring Design Language (024)

**Feature**: `specs/024-visual-language-migration/` | **Branch**: `feature/024-visual-language-migration`
**Status**: Implemented (T001–T036); pending product visual sign-off (SC-005) and 022/023 merge.

## What changed

The entire frontend now uses the "monitoring" visual language introduced by Delta Sync (023,
`frontend/design_handoff_delta_sync/`). The old shadcn-default language (dark navy primary,
`border-gray-200` cards, uppercase gray table headers, `bg-blue-600` buttons, solid dark badges)
is fully removed — enforced by grep audits (see Verification).

### Token architecture (single source)

1. **`frontend/src/shared/ui/tokens.ts`** — canonical `monitoringTokens` + `severityTokens`
   (promoted verbatim from `features/delta-sync/model/tokens.ts`, which was deleted; the
   feature's public API re-exports the shared module).
2. **CSS variables** (`shared/styles/index.css`) — shadcn vars remapped: `--primary` #3C82D8,
   `--foreground` #2B2827, `--muted-foreground` #736F6D, `--border/--input` hairline-on-white,
   `--ring` brand, `--radius` 10px. `.dark` block untouched (out of scope).
3. **Tailwind theme** (`tailwind.config.js`) — semantic utilities mirroring the tokens:
   `ink.*` text scale, `brand.*`, `surface.{subtle,hover,active,shell}`, `hairline`/`separator`,
   `danger.*`, `warn.*`, `shadow-card`/`shadow-card-inner`/`shadow-icon-circle`,
   `aria-invalid:` variant.

### Restyled primitives (`shared/ui/ui/*`)

- **Badge** — alpha pills `info|neutral|success|warning|critical|stalled|outline` + `dot` prop
  (6px status dot); 12px/500; old variants deleted.
- **Button** — `default` brand solid, `outline` hairline, new `destructive-outline`,
  `destructive` solid red for dialog confirms, `compact` (h-8) size, rounded-lg.
- **Card** — borderless white r10 + layered `shadow-card`; CardTitle 15px/500 −0.24px.
- **Table** — 12px/500 headers (no uppercase), hairline rows, `#FAFAFA` hover, `tableCellNumeric`.
- **Tabs** — site-detail pill treatment is now the default.
- **Input/Select/Checkbox** — hairline borders, brand focus, `aria-invalid` danger treatment.
- **Skeleton/Alert/Separator/Dialogs** — subtle surfaces, danger warning panels, hairlines.
- **New**: `shared/ui/page-header.tsx` (22px/500/−0.33px title + subline + slots).

### Migrated surfaces

Header/shell; Dashboard (charts on Card + token chart chrome, GlobalErrors); Site Management
(incl. finishing the partially-migrated SiteListItem); Upload History v1 (BatchListView,
FileTable, BatchDetailView, ErrorListView); Comparisons (list/card/detail/diff chrome — diff
highlight semantics unchanged); Plugins user + admin (tabs → primitive, audit/SQL tables,
status badges); Admin accounts (list, TanStack table, details, dialogs, pagination);
device-verify; ErrorBoundary; entity badges (`AccountStatusBadge`, `PluginStatusBadge`,
`ActionTypeBadge` → Badge pills). Delta Sync surfaces re-pointed to shared tokens with zero
pixel drift (local tab override and hardcoded hexes removed).

### Explicitly untouched

- **P2 (owner lite segments)** — admin-only segments/throughput gating unchanged.
- `.dark` CSS block; native `<select>`s kept (restyled with token classes) for a11y/test stability.
- No behavior/data/API changes anywhere (visual-only; FR-005).

## Verification

- Per-task gate `npm --prefix frontend test` green after every task (T001–T036); suite:
  96 files / 937 tests passing.
- **Audit 1 (old language)**: grep for `border-gray-200|bg-blue-600|uppercase tracking-wider|`
  `focus:ring-blue-500|bg-*-100 text-*-800|text-gray-900/700/600/500|min-h-screen bg-gray-50|`
  `border-t-blue-600` over `frontend/src` (excl. tests) → **0 hits**.
- **Audit 2 (single token source)**: monitoring hexes (`#3C82D8`, `#2B2827`, `#736F6D`, card
  shadow) appear only in `shared/ui/tokens.ts` → **clean**.
- Manual per-route visual checklist (`specs/024-visual-language-migration/quickstart.md`) —
  to be walked with product for SC-005 sign-off.

## Related docs

- Spec/plan/tasks/migration table: `specs/024-visual-language-migration/`
- Delta Sync data-wiring audit (product concern closed, zero defects):
  `specs/024-visual-language-migration/research.md` §4
- Design reference: `frontend/design_handoff_delta_sync/README.md` + prototype
