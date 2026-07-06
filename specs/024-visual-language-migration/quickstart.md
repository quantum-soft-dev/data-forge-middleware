# Quickstart & Verification: Visual Language Migration (024)

## Run the frontend against a live backend

```bash
docker compose up -d postgres localstack redis
local-dev/run-with-auth0.sh                 # backend :8080 (real Auth0 tenant dev-dfm; needs local-dev/auth0.env)
npm --prefix frontend install
npm --prefix frontend run dev               # Vite proxies /api → :8080
```

## Per-task gate (Development Policy Rule 2)

```bash
npm --prefix frontend test                  # must be 100% green before every commit
```

(The pre-commit hook additionally runs `./gradlew test -PexcludeIntegration`; no backend files
change in this feature, so it stays green.)

## Mechanical old-language audit (SC-001, FR-007) — run after Phase D, expect 0 hits

```bash
cd frontend/src
grep -rn --include='*.tsx' --include='*.ts' --include='*.css' -E \
  'border-gray-200|bg-blue-600|hover:bg-blue-700|uppercase tracking-wider|focus:ring-blue-500|bg-(green|red|yellow|blue)-100 text-(green|red|yellow|blue)-800|text-gray-(900|700|600|500)|min-h-screen bg-gray-50|border-t-blue-600' \
  . | grep -v '\.test\.' || echo "AUDIT CLEAN"
```

## Single-token-source audit (SC-004) — hex literals only in the token module / CSS vars

```bash
cd frontend/src
grep -rn --include='*.tsx' -E '#3C82D8|#3676C4|#2B2827|#736F6D|#A3A3A3|87\.5px' . \
  | grep -v 'shared/ui/tokens.ts' | grep -v '\.test\.' || echo "TOKEN SOURCE CLEAN"
```

(During Phases A-C these greps shrink monotonically; record the count in each C-task commit
message if useful. Zero is required only at D3.)

## Manual visual checklist (SC-005, per migrated surface)

For each route — Dashboard `/`, Sites `/account/sites`, Site detail `/account/sites/:id` (both
tabs), Upload history, Batch detail (v1 + delta), Comparisons (list/detail), My Plugins
(all tabs), Admin: accounts (list/detail/dialogs), sites, plugins (3 tabs), settings,
`/device-verify` (3 states), login:

- [ ] Typography: Geist, titles 22px/500 −0.33px, no `font-bold`/`text-3xl`, numbers `tabular-nums`
- [ ] Statuses are alpha pills (dot where status-like); no dark solid badges
- [ ] Cards: white r10 + layered shadow; no `border-gray-200` frames
- [ ] Tables: 12px/500 headers (no uppercase), hairline rows, `#FAFAFA` hover
- [ ] Primary actions `#3C82D8`→`#3676C4`; destructive `#B91C1C`/red per model
- [ ] Focus states visible (`#3C82D8` ring) on inputs/buttons — keyboard pass
- [ ] Compare against `frontend/design_handoff_delta_sync/prototype/Delta Sync.dc.html` tokens

## Delta Sync wiring spot-check (closes the product concern; see research.md §4)

```bash
curl -s localhost:8080/v3/api-docs | jq '.paths | keys | map(select(contains("delta")))'  # 12 paths
docker exec dfm-postgres psql -U dfm -d dfm -c "select site_id,last_applied_seq,updated_at from site_sync_state;"
# UI: open Delta Sync tab of a V2 site; lag/checkpoints values must update on 20s/30s polls
```
