#!/usr/bin/env bash
# Управление issue на доске GitHub Project «Data Forge Middleware — Sprints» без ручных API-вызовов.
#   scripts/board.sh status <issue#> <Backlog|Ready|In Progress|Blocked|In Review|Ready To Merge|Done>
#   scripts/board.sh unblock <closed-issue#>   # снять blocked с issues, у которых все «Blocked by» закрыты
#   scripts/board.sh show <issue#>
#
# Статус живёт в двух местах, и оба двигаются вместе (CLAUDE.md → «Status lives in two places»):
# колонка на доске и метка `status: *` в репозитории. «Ready To Merge» — не колонка: карточка
# остаётся в `In Review`, а готовность показывает метка `status: ready to merge`.
set -euo pipefail
OWNER=quantum-soft-dev; REPO=quantum-soft-dev/data-forge-middleware; PROJECT=16

# Резолвится лениво: `show` id проекта не нужен вовсе, а безусловный вызов стоил бы лишнего
# обращения к API на каждом запуске скрипта. Функция вызывается ОТДЕЛЬНЫМ ОПЕРАТОРОМ, а не
# через `$(…)`: подстановка команды исполняется в субшелле, присваивание `PID` в родителя не
# возвращается, и «кэш» перестал бы кэшировать — в `unblock_after`, где `set_status` зовётся в
# цикле, это был бы поход в API на каждый разблокированный тикет.
PID=""
ensure_project_id() { [[ -n "$PID" ]] || PID=$(gh project view "$PROJECT" --owner "$OWNER" --format json --jq .id); }

item_id() { gh project item-list "$PROJECT" --owner "$OWNER" --limit 500 --format json \
  --jq ".items[] | select(.content.number==$1) | .id"; }

# Колонка доски для запрошенного статуса (Ready To Merge живёт в In Review).
column_for() { [[ "$1" == "Ready To Merge" ]] && echo "In Review" || echo "$1"; }

# Метка `status: *` для запрошенного статуса; пусто — значит статусных меток быть не должно.
label_for() {
  case "$1" in
    Ready)            echo "status: ready" ;;
    "In Progress")    echo "status: in progress" ;;
    Blocked)          echo "status: blocked" ;;
    "In Review")      echo "status: in review" ;;
    "Ready To Merge") echo "status: ready to merge" ;;
    Backlog|Done)     echo "" ;;
    *) echo "unknown status: $1" >&2; exit 1 ;;
  esac
}

# Снять все реально висящие `status: *` — список не хардкодят: --remove-label отвечает 404,
# если названной метки на тикете нет (это #257).
strip_status_labels() {
  local issue=$1 keep=${2:-}
  while IFS= read -r label; do
    [[ -n "$keep" && "$label" == "$keep" ]] && continue
    gh issue edit "$issue" -R "$REPO" --remove-label "$label" >/dev/null 2>&1 || true
  done < <(gh issue view "$issue" -R "$REPO" --json labels \
             --jq '.labels[].name | select(startswith("status:"))')
}

set_status() {
  local issue=$1 status=$2
  local column label; column=$(column_for "$status"); label=$(label_for "$status")

  local item; item=$(item_id "$issue")
  if [[ -z "$item" ]]; then
    gh project item-add "$PROJECT" --owner "$OWNER" \
      --url "https://github.com/$REPO/issues/$issue" >/dev/null
    item=$(item_id "$issue")
  fi
  [[ -z "$item" ]] && { echo "issue #$issue не удалось добавить на доску" >&2; exit 1; }

  local fields fid oid
  fields=$(gh project field-list "$PROJECT" --owner "$OWNER" --format json)
  fid=$(jq -r '.fields[] | select(.name=="Status") | .id' <<<"$fields")
  oid=$(jq -r --arg s "$column" '.fields[] | select(.name=="Status") | .options[] | select(.name==$s) | .id' <<<"$fields")
  [[ -z "$oid" || "$oid" == "null" ]] && { echo "на доске нет колонки: $column" >&2; exit 1; }
  ensure_project_id
  gh project item-edit --project-id "$PID" --id "$item" --field-id "$fid" \
    --single-select-option-id "$oid" >/dev/null

  strip_status_labels "$issue" "$label"
  [[ -n "$label" ]] && gh issue edit "$issue" -R "$REPO" --add-label "$label" >/dev/null

  # Проверяем результат, а не факт запуска: отработавшая команда — не доказательство перехода.
  local now; now=$(gh project item-list "$PROJECT" --owner "$OWNER" --limit 500 --format json \
    --jq ".items[] | select(.content.number==$issue) | .status")
  echo "#$issue → $now${label:+  [$label]}"
}

unblock_after() {
  local closed=$1
  gh issue list -R "$REPO" --label "status: blocked" --state open --limit 200 --json number,body \
    --jq '.[] | "\(.number)\t\(.body // "" | gsub("\n";" "))"' | while IFS=$'\t' read -r num body; do
    deps=$(grep -oE 'Blocked by (#[0-9]+(, ?)?)+' <<<"$body" | grep -oE '[0-9]+' | sort -u || true)
    grep -qx "$closed" <<<"$deps" || continue
    all_closed=1
    for d in $deps; do
      [[ "$(gh issue view "$d" -R "$REPO" --json state --jq .state)" == "CLOSED" ]] || { all_closed=0; break; }
    done
    [[ $all_closed == 1 ]] && set_status "$num" Ready
  done
}

case "${1:-}" in
  status)  set_status "$2" "$3" ;;
  unblock) unblock_after "$2" ;;
  show)    gh issue view "$2" -R "$REPO" --json number,title,state,labels,milestone \
             --jq '"#\(.number) \(.title) [\(.state)] \([.labels[].name]|join(","))"' ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
