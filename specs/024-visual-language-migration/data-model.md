# Data Model: Visual Language Migration (024)

No persistent data changes (FR-005). The "entities" of this feature are design tokens and
primitive variants — modeled here so implementation and review share one vocabulary.

## 1. Token model (`frontend/src/shared/ui/tokens.ts`, promoted verbatim from `features/delta-sync/model/tokens.ts`)

### `monitoringTokens` (const object)

| Token | Value | Consumed via |
|---|---|---|
| `primary` / `primaryHover` | `#3C82D8` / `#3676C4` | CSS var `--primary`, Tailwind `brand`, Button default |
| `blue50` / `blue100` | `#EBF2FB` / `#E0ECFA` | Tailwind `brand-50/100`; info pills, selected rows |
| `text` / `textSecondary` / `textMuted` / `title` | `#2B2827` / `#736F6D` / `#A3A3A3` / `#403C3B` | CSS vars `--foreground`/`--muted-foreground`; Tailwind `ink.*` |
| `subtleBg` / `hoverRow` / `metricShell` | `#F5F5F4` / `#FAFAFA` / `#EFEFEF` | hover states, page bg, metric shells |
| `border` / `separator` / `iconWellBorder` | `rgba(0,0,0,0.12)` / `rgba(0,0,0,0.06)` / `rgba(24,22,22,0.08)` | CSS var `--border`; Tailwind `border-hairline`/`border-separator` |
| `cardShadow` / `innerCardShadow` / `iconCircleShadow` | layered soft shadows (see tokens.ts) | Tailwind `shadow-card` / `shadow-card-inner` / `shadow-icon-circle` |
| `barGradient` | `linear-gradient(180deg,#3C82D8,#C9DCF4)` | inline style (charts/bars only) |

### `severityTokens` (record keyed by `SyncSeverity`, reused as generic status palette)

| Key | dot | text | bg (alpha) | Generalized meaning in 024 |
|---|---|---|---|---|
| `healthy` | `#16A34A` | `#15803D` | `rgba(22,163,74,0.10)` | success / active / completed |
| `elevated` | `#F59E0B` | `#B45309` | `rgba(245,158,11,0.12)` | warning / stale / pending |
| `critical` | `#EF4444` | `#B91C1C` | `rgba(239,68,68,0.12)` | error / failed / blocked / unread |
| `stalled` | `#F97316` | `#C2410C` | `rgba(249,115,22,0.12)` | stalled / timeout |

### Typography rules (not in tokens.ts; encoded in primitives/PageHeader)

| Role | Size/weight | Tracking | Notes |
|---|---|---|---|
| Metric headline | 34px/500 | −0.42px | `tabular-nums` |
| Page/section title | 22px/500 | −0.33px | PageHeader |
| Card title | 15px/500 | −0.24px | CardTitle |
| Body | 14px/400 | — | default |
| Meta / secondary | 13px/500 | — | breadcrumbs, metadata |
| Pill / table header | 12px/500 | — | never uppercase |
| Axis/labels | 11px/400 | — | muted |

## 2. CSS variable mapping (`shared/styles/index.css`, light block)

| Variable | Old (shadcn default) | New |
|---|---|---|
| `--primary` | `222.2 47.4% 11.2%` (navy) | `213 66% 54%` (`#3C82D8`) |
| `--primary-foreground` | near-white | white |
| `--foreground` | `222.2 84% 4.9%` | `#2B2827` equivalent |
| `--muted-foreground` | `215.4 16.3% 46.9%` | `#736F6D` equivalent |
| `--border` / `--input` | `214.3 31.8% 91.4%` (≈gray-200) | hairline (`rgba(0,0,0,0.12)`; if HSL-only pipeline blocks alpha → keep var + introduce `--border-hairline`) |
| `--ring` | navy | `#3C82D8` |
| `--radius` | `0.5rem` | `10px` |
| `--destructive` | `0 84.2% 60.2%` | keep hue, align hover/derived usages to `#EF4444`/`#DC2626`/`#B91C1C` |
| `.dark` block | — | untouched (out of scope) |

## 3. Primitive variant model (target API)

### Badge (`shared/ui/ui/badge.tsx`)

```
variant: info | neutral | success | warning | critical | stalled | outline
dot?: boolean          // 6px leading dot in the variant's dot color
```
Mapping from old code: `default`→`info`, `secondary`→`neutral`, `destructive`→`critical`,
inline `bg-green-100 text-green-800`→`success`, etc. (research.md D3).
Consumers to collapse onto Badge: `AccountStatusBadge`, `PluginStatusBadge`, inline status spans
in BatchListView/PluginCard/GlobalErrors.

### Button (`shared/ui/ui/button.tsx`)

```
variant: default (#3C82D8/#3676C4) | outline (hairline) | ghost | link
       | destructive (solid #EF4444→#DC2626) | destructive-outline (#B91C1C text, red hairline)
size: default | sm | lg | icon | compact (h-8, per CheckpointsCard:109)
```

### Card / Table / Tabs / Input-Select / Dialog / Skeleton-Alert-Separator

See migration-table.md rows A6-A11 — no API changes, class-level restyle only
(Table keeps `<table>` semantics per research D4).

### PageHeader (`shared/ui/page-header.tsx`, new)

```
props: title: string; subtitle?: string; actions?: ReactNode; breadcrumb?: ReactNode
```
Renders the 22px/−0.33px title + secondary subline per `SiteDetailShell.tsx:35-110`.

## 4. State transitions

None — visual-only feature. The only lifecycle is per-surface migration status tracked as
row-completion in `migration-table.md` / task checkboxes in `tasks.md`.
