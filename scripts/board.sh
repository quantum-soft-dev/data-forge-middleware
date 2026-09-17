#!/usr/bin/env bash
# Управление issue на доске GitHub Project «Data Forge Middleware — Sprints» без ручных API-вызовов.
#   scripts/board.sh status <issue#> <Backlog|Ready|In Progress|Blocked|In Review|Ready To Merge|Done>
#   scripts/board.sh unblock <closed-issue#>   # снять blocked с issues, у которых все «Blocked by» закрыты
#   scripts/board.sh list <колонка> [<колонка>…] # открытые issues в колонках: «#n<TAB>колонка<TAB>заголовок»
#   scripts/board.sh show <issue#>
#
# Статус живёт в двух местах, и оба двигаются вместе (CLAUDE.md → «Status lives in two places»):
# колонка на доске и метка `status: *` в репозитории. «Ready To Merge» — не колонка: карточка
# остаётся в `In Review`, а готовность показывает метка `status: ready to merge`.
#
# GraphQL-бюджет — 5000 очков в час на АККАУНТ, общий для всех сессий, субагентов и скриптов (#311).
# Поэтому здесь нет ни одного `gh project …`: `item-list --limit 500` стоит ~200 очков, `field-list`
# ~100, и прежняя версия тратила ~500 на одну смену статуса. Вместо этого:
#   - карточка, id проекта, поля и колонок — ОДНИМ запросом (1 очко); id резолвятся по именам на каждом
#     вызове, так что копий id из CLAUDE.md («Board identifiers») здесь нет и расходиться нечему;
#   - добавление на доску и смена колонки — прямыми мутациями, проверка — запросом одной карточки;
#   - метки и состояние issue — через REST (`gh api repos/…`): у него свой пул, GraphQL не тратится;
#   - `list` читает доску по `fieldValueByName` — 1 очко на страницу из 100 карточек.
# JSON разбирается здесь же через jq, а не флагом `--jq`: так один ответ читается несколько раз.
set -euo pipefail
OWNER=quantum-soft-dev; REPO=quantum-soft-dev/data-forge-middleware; PROJECT=16

fail() { echo "board.sh: $*" >&2; exit 1; }
uri() { jq -rn --arg s "$1" '$s|@uri'; }

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

# Всё, что нужно для смены статуса, одним запросом: node id issue, её карточки (по всем проектам —
# фильтр по номеру ниже) и поле Status проекта со всеми колонками.
lookup() {
  gh api graphql -F n="$1" -F project="$PROJECT" -f owner="$OWNER" -f repo="${REPO#*/}" \
    -f query='query($owner:String!,$repo:String!,$n:Int!,$project:Int!){repository(owner:$owner,name:$repo){issue(number:$n){id projectItems(first:20){nodes{id project{number}}}}} organization(login:$owner){projectV2(number:$project){id field(name:"Status"){... on ProjectV2SingleSelectField{id options{id name}}}}}}'
}

# Снять все реально висящие `status: *`, кроме `keep` — список не хардкодят: DELETE отвечает 404,
# если метки на тикете нет (это #257).
strip_status_labels() {
  # `label` обязательно local: без этого `read` пишет в `label` вызывающей set_status (динамическая
  # область видимости bash) и на EOF оставляет её пустой — статусная метка молча не ставилась (#309).
  local issue=$1 keep=${2:-} label
  while IFS= read -r label; do
    [[ -n "$keep" && "$label" == "$keep" ]] && continue
    gh api -X DELETE "repos/$REPO/issues/$issue/labels/$(uri "$label")" >/dev/null 2>&1 || true
  done < <(gh api -X GET "repos/$REPO/issues/$issue/labels?per_page=100" \
             | jq -r '.[].name | select(startswith("status:"))')
}

set_status() {
  local issue=$1 status=$2
  local column label; column=$(column_for "$status"); label=$(label_for "$status")

  local data project field option content item
  data=$(lookup "$issue")
  project=$(jq -r '.data.organization.projectV2.id // empty' <<<"$data")
  field=$(jq -r '.data.organization.projectV2.field.id // empty' <<<"$data")
  [[ -n "$project" && -n "$field" ]] || fail "не найдены проект $PROJECT или его поле Status"
  option=$(jq -r --arg c "$column" '.data.organization.projectV2.field.options[] | select(.name==$c) | .id' <<<"$data")
  [[ -n "$option" ]] || fail "на доске нет колонки: $column"
  content=$(jq -r '.data.repository.issue.id // empty' <<<"$data")
  [[ -n "$content" ]] || fail "issue #$issue не найден"
  item=$(jq -r --argjson p "$PROJECT" '.data.repository.issue.projectItems.nodes[] | select(.project.number==$p) | .id' <<<"$data")

  if [[ -z "$item" ]]; then
    item=$(gh api graphql -f project="$project" -f content="$content" \
      -f query='mutation($project:ID!,$content:ID!){addProjectV2ItemById(input:{projectId:$project,contentId:$content}){item{id}}}' \
      | jq -r '.data.addProjectV2ItemById.item.id // empty')
    [[ -n "$item" ]] || fail "issue #$issue не удалось добавить на доску"
  fi

  gh api graphql -f project="$project" -f item="$item" -f field="$field" -f option="$option" \
    -f query='mutation($project:ID!,$item:ID!,$field:ID!,$option:String!){updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){projectV2Item{id}}}' \
    >/dev/null

  strip_status_labels "$issue" "$label"
  if [[ -n "$label" ]]; then
    gh api -X POST "repos/$REPO/issues/$issue/labels" -f "labels[]=$label" >/dev/null
  fi

  # Проверяем результат, а не факт запуска: отработавшая команда — не доказательство перехода.
  local now
  now=$(gh api graphql -f item="$item" \
    -f query='query($item:ID!){node(id:$item){... on ProjectV2Item{fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}}}}}' \
    | jq -r '.data.node.fieldValueByName.name // "?"')
  echo "#$issue → $now${label:+  [$label]}"
}

unblock_after() {
  local closed=$1 num body deps d all_closed
  # REST: список issue с меткой (PR в этом эндпоинте тоже бывают — отбрасываем их).
  while IFS=$'\t' read -r num body; do
    deps=$(grep -oE 'Blocked by (#[0-9]+(, ?)?)+' <<<"$body" | grep -oE '[0-9]+' | sort -u || true)
    grep -qx "$closed" <<<"$deps" || continue
    all_closed=1
    for d in $deps; do
      [[ "$(gh api -X GET "repos/$REPO/issues/$d" | jq -r .state)" == closed ]] || { all_closed=0; break; }
    done
    if [[ $all_closed == 1 ]]; then set_status "$num" Ready; fi
  done < <(gh api -X GET "repos/$REPO/issues?state=open&per_page=100&labels=$(uri "status: blocked")" --paginate \
             | jq -r '.[] | select(.pull_request | not) | "\(.number)\t\(.body // "" | gsub("[\r\n]";" "))"')
}

list_columns() {
  (($#)) || fail "list: назови хотя бы одну колонку"
  local wanted cursor="" page
  wanted=$(printf '%s\n' "$@" | jq -R . | jq -s .)
  while :; do
    local args=(-F project="$PROJECT" -f owner="$OWNER")
    [[ -n "$cursor" ]] && args+=(-f cursor="$cursor")
    page=$(gh api graphql "${args[@]}" \
      -f query='query($owner:String!,$project:Int!,$cursor:String){organization(login:$owner){projectV2(number:$project){items(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}} content{... on Issue{number title state}}}}}}}')
    jq -r --argjson wanted "$wanted" '.data.organization.projectV2.items.nodes[]
      | select(.content.number != null and .content.state == "OPEN")
      | select(.fieldValueByName.name as $c | $c != null and ($wanted | index($c)) != null)
      | "#\(.content.number)\t\(.fieldValueByName.name)\t\(.content.title)"' <<<"$page"
    [[ "$(jq -r '.data.organization.projectV2.items.pageInfo.hasNextPage' <<<"$page")" == true ]] || break
    cursor=$(jq -r '.data.organization.projectV2.items.pageInfo.endCursor' <<<"$page")
  done
}

case "${1:-}" in
  status)  set_status "$2" "$3" ;;
  unblock) unblock_after "$2" ;;
  list)    shift; list_columns "$@" ;;
  show)    gh api -X GET "repos/$REPO/issues/$2" \
             | jq -r '"#\(.number) \(.title) [\(.state | ascii_upcase)] \([.labels[].name] | join(","))"' ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
