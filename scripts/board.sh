#!/usr/bin/env bash
# Управление issue на доске GitHub Project «Data Forge Middleware — Sprints» без ручных API-вызовов.
#   scripts/board.sh status <issue#> <Backlog|Ready|In Progress|Blocked|In Review|Ready To Merge|Done>
#   scripts/board.sh field <issue#> Size <XS|S|M|L|XL>  # поле доски; метки и тело не трогает
#   scripts/board.sh unblock <closed-issue#>   # снять blocked с issues, у которых все «Blocked by» закрыты
#   scripts/board.sh list <колонка> [<колонка>…] # открытые issues в колонках: «#n<TAB>колонка<TAB>заголовок»
#   scripts/board.sh items                     # все карточки: «#n<TAB>колонка<TAB>OPEN|CLOSED<TAB>заголовок»
#   scripts/board.sh show <issue#>
#   scripts/board.sh sweep [--fix]             # сверка доски с реальностью; --fix чинит однозначное
#
# Статус живёт в двух местах, и оба двигаются вместе (CLAUDE.md → «Status lives in two places»):
# колонка на доске и метка `status: *` в репозитории. «Ready To Merge» — не колонка: карточка
# остаётся в `In Review`, а готовность показывает метка `status: ready to merge`.
#
# GraphQL-бюджет — 5000 очков в час на АККАУНТ, общий для всех сессий, субагентов и скриптов (#311).
# Поэтому здесь нет ни одного `gh project …`: `item-list --limit 500` стоит ~200 очков, `field-list`
# ~100, и прежняя версия тратила ~500 на одну смену статуса. Вместо этого:
#   - карточка, id проекта, поле и его значения — ОДНИМ запросом (1 очко); id резолвятся по именам на
#     каждом вызове, так что копий id из CLAUDE.md («Board identifiers») здесь нет и расходиться нечему;
#   - добавление на доску и смена значения — прямыми мутациями, проверка — запросом одной карточки;
#   - метки и состояние issue — через REST (`gh api repos/…`): у него свой пул, GraphQL не тратится;
#   - `list`, `items` и `sweep` читают доску по `fieldValueByName` — 1 очко на страницу из 100 карточек.
# Итого: `status` и `field` — по 3 очка (4 при первом добавлении на доску).
# JSON разбирается здесь же через jq, а не флагом `--jq`: так один ответ читается несколько раз.
#
# Priority на этой доске — не поле с выбором, а метка `priority: high|medium|low`, поэтому `field`
# принимает только Size. Только bash 3.2 (это /bin/bash на macOS): без ${var,,} и ассоциативных массивов.
set -euo pipefail
OWNER=quantum-soft-dev; REPO=quantum-soft-dev/data-forge-middleware; PROJECT=16
# Где искать локальные ветки и worktree для `sweep` (ABANDONED). Переопределяется только тестом.
GIT_ROOT="${BOARD_GIT_ROOT:-$(cd "$(dirname "$0")" && pwd)}"

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

# Всё, что нужно для смены значения поля, одним запросом: node id issue, её карточки (по всем
# проектам — фильтр по номеру ниже) и поле проекта по имени (Status, Size) со всеми значениями.
lookup() {
  gh api graphql -F n="$1" -F project="$PROJECT" -f owner="$OWNER" -f repo="${REPO#*/}" -f fieldName="$2" \
    -f query='query($owner:String!,$repo:String!,$n:Int!,$project:Int!,$fieldName:String!){repository(owner:$owner,name:$repo){issue(number:$n){id projectItems(first:20){nodes{id project{number}}}}} organization(login:$owner){projectV2(number:$project){id field(name:$fieldName){... on ProjectV2SingleSelectField{id options{id name}}}}}}'
}

# Поставить значение поля с одиночным выбором на карточку issue; карточки нет — добавить на доску.
# Печатает значение, перечитанное с доски: проверяется результат, а не факт запуска.
set_field() {
  local issue=$1 name=$2 value=$3
  local data project field option content item options
  data=$(lookup "$issue" "$name")
  project=$(jq -r '.data.organization.projectV2.id // empty' <<<"$data")
  field=$(jq -r '.data.organization.projectV2.field.id // empty' <<<"$data")
  [[ -n "$project" && -n "$field" ]] || fail "не найдены проект $PROJECT или его поле $name"
  option=$(jq -r --arg v "$value" '.data.organization.projectV2.field.options[] | select(.name==$v) | .id' <<<"$data")
  if [[ -z "$option" ]]; then
    [[ "$name" == Status ]] && fail "на доске нет колонки: $value"
    options=$(jq -r '.data.organization.projectV2.field.options | map(.name) | join(", ")' <<<"$data")
    fail "у поля $name нет значения «$value» (есть: $options)"
  fi
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

  gh api graphql -f item="$item" -f fieldName="$name" \
    -f query='query($item:ID!,$fieldName:String!){node(id:$item){... on ProjectV2Item{fieldValueByName(name:$fieldName){... on ProjectV2ItemFieldSingleSelectValue{name}}}}}' \
    | jq -r '.data.node.fieldValueByName.name // "?"'
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
  local column label now; column=$(column_for "$status"); label=$(label_for "$status")

  # Метка — после колонки: колонка не встала (set_field упал) — метку не трогаем.
  now=$(set_field "$issue" Status "$column") || exit 1

  strip_status_labels "$issue" "$label"
  if [[ -n "$label" ]]; then
    gh api -X POST "repos/$REPO/issues/$issue/labels" -f "labels[]=$label" >/dev/null
  fi
  echo "#$issue → $now${label:+  [$label]}"
}

# Size — поле доски без пары в метках: только значение на карточке. Status сюда не пускается: мимо
# `status` он разошёлся бы с меткой `status: *`; Priority на этой доске — метка `priority: *`.
set_board_field() {
  local usage="field <issue#> Size <XS|S|M|L|XL>"
  [[ $# == 3 && "$1" =~ ^[0-9]+$ && -n "$3" ]] || fail "$usage"
  case "$2" in
    Size) ;;
    Status) fail "field: Status меняет только board.sh status — он двигает и колонку, и метку status: *" ;;
    Priority) fail "field: Priority здесь — метка priority: high|medium|low (gh api …/labels), не поле доски" ;;
    *) fail "field: поле Size, а не «$2»" ;;
  esac
  local now
  now=$(set_field "$1" "$2" "$3") || exit 1
  echo "#$1 $2 → $now"
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

# Все карточки доски одной постраничной выборкой, 1 очко на 100 карточек:
# «#n<TAB>колонка|-<TAB>OPEN|CLOSED<TAB>заголовок». `list` — тот же обход с фильтром.
board_items() {
  local cursor="" page
  while :; do
    local args=(-F project="$PROJECT" -f owner="$OWNER")
    [[ -n "$cursor" ]] && args+=(-f cursor="$cursor")
    page=$(gh api graphql "${args[@]}" \
      -f query='query($owner:String!,$project:Int!,$cursor:String){organization(login:$owner){projectV2(number:$project){items(first:100,after:$cursor){pageInfo{hasNextPage endCursor} nodes{fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}} content{... on Issue{number title state}}}}}}}')
    jq -r '.data.organization.projectV2.items.nodes[]
      | select(.content.number != null)
      | "#\(.content.number)\t\(.fieldValueByName.name // "-")\t\(.content.state)\t\(.content.title)"' <<<"$page"
    [[ "$(jq -r '.data.organization.projectV2.items.pageInfo.hasNextPage' <<<"$page")" == true ]] || break
    cursor=$(jq -r '.data.organization.projectV2.items.pageInfo.endCursor' <<<"$page")
  done
}

list_columns() {
  (($#)) || fail "list: назови хотя бы одну колонку"
  local wanted
  wanted=$(printf '%s|' "$@")
  board_items | WANTED="$wanted" awk -F'\t' '
    BEGIN { n = split(ENVIRON["WANTED"], a, "|"); for (i = 1; i <= n; i++) if (a[i] != "") w[a[i]] = 1 }
    $3 == "OPEN" && ($2 in w) { print $1 "\t" $2 "\t" $4 }'
}

# REST, все страницы одним массивом.
rest_all() { gh api -X GET "$1" --paginate | jq -s 'add // []'; }

# Сплошная сверка доски с реальностью. `unblock` точечный — он смотрит только на issue, перечисляющие
# ТОЛЬКО ЧТО закрытый номер; всё, что разошлось иначе (блокер закрыт руками, колонку перебила
# автоматизация доски — «Pull request linked to issue», «Item added to project», — задачу бросили),
# находит только эта сверка. Строка на расхождение: «ВИД<TAB>#n<TAB>что нашли<TAB>что предлагается».
#   STALE_BLOCK  `status: blocked`, все «Blocked by» закрыты               → Ready        (чинит --fix)
#   MANUAL_BLOCK `status: blocked` без «Blocked by» или со ссылкой на
#                несуществующую issue                                       → решает человек
#   COLUMN       колонка ≠ метке `status: *` (или карточки нет)             → колонка по метке (--fix)
#   ABANDONED    `in progress`/`in review`/`ready to merge`, но нет
#                исполнителя, ветки `*/<n>-*`, worktree и открытого PR      → решает человек
#   CLOSED       issue закрыта, а метка `status: *` висит или карточка не в
#                Done                                                        → Done / снять метки (--fix)
# Колонка чинится ПО МЕТКЕ: метку ставит конвейер через `status`, а колонку может перебить
# автоматизация доски. `status: ready to merge` — это колонка `In Review`. Брошенные задачи не чинятся
# никогда: снаружи не видно, ведёт ли их кто-то прямо сейчас в своей сессии.
# Стоимость: `board_items` (1 очко на 100 карточек), остальное — REST; --fix — по 3 очка на исправление.
sweep() {
  local fix=0
  case "${1:-}" in
    "") ;;
    --fix) fix=1 ;;
    *) fail "sweep: единственный флаг — --fix" ;;
  esac

  local open closed prs branches wts items report
  open=$(rest_all "repos/$REPO/issues?state=open&per_page=100")
  closed=$(rest_all "repos/$REPO/issues?state=closed&per_page=100" \
             | jq '[.[] | {number, pr: (.pull_request != null), labels: [.labels[].name | select(startswith("status:"))]}]')
  prs=$(rest_all "repos/$REPO/pulls?state=open&per_page=100")
  # Ветки: удалённые (REST) и локальные этой машины — работа могла ещё не уйти в push.
  branches=$({ rest_all "repos/$REPO/branches?per_page=100" | jq -r '.[].name'
               git -C "$GIT_ROOT" for-each-ref --format='%(refname:short)' refs/heads 2>/dev/null || true
             } | jq -R . | jq -s 'unique')
  # Worktree волны создаются в detached HEAD — ветки у них ещё нет, номер виден только по пути
  # (`.claude/worktrees/<n>-…` у /task и /wave, `.worktrees/<n>-…` у /github-issue).
  wts=$({ git -C "$GIT_ROOT" worktree list --porcelain 2>/dev/null || true; } \
          | sed -n 's#^worktree .*worktrees/\([0-9][0-9]*\)-.*#\1#p' | jq -R 'tonumber' | jq -s 'unique')
  items=$(board_items | jq -R 'split("\t") | {key: (.[0] | ltrimstr("#")), value: .[1]}' | jq -s 'from_entries')

  report=$(jq -r --argjson closed "$closed" --argjson prs "$prs" --argjson branches "$branches" \
      --argjson wts "$wts" --argjson items "$items" '
    def has($x): any(.[]; . == $x);
    def deps: [(.body // "") | scan("Blocked by ((?:#[0-9]+(?:, ?)?)+)") | .[0] | scan("[0-9]+") | tonumber] | unique;
    def slabels: [.labels[].name | select(startswith("status:"))] | sort;
    def column_for($l): {"status: ready": "Ready", "status: in progress": "In Progress",
                         "status: blocked": "Blocked", "status: in review": "In Review",
                         "status: ready to merge": "In Review"}[$l];
    def col_or_dash($c): if $c == null or $c == "-" then null else $c end;

    [.[] | select(.pull_request | not)] as $issues
    | ([.[] | .number]) as $open
    | ($closed | map(.number)) as $closednums
    | ([$prs[] | ((.body // "") | scan("(?i)(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?) #([0-9]+)") | .[0] | tonumber),
                 (.head.ref | scan("/([0-9]+)-") | .[0] | tonumber)] | unique) as $withpr
    | ([$branches[] | scan("/([0-9]+)-") | .[0] | tonumber] | unique) as $withbranch
    | ($issues[]
    | .number as $n
    | slabels as $sl
    | col_or_dash($items[$n | tostring]) as $col
    | deps as $d
    | ([$d[] | select(. as $x | $closednums | has($x) | not)]) as $notclosed
    | ([$notclosed[] | select(. as $x | $open | has($x) | not)]) as $unknown
    | ($sl | has("status: blocked")) as $blocked
    | ($blocked and ($d | length) > 0 and ($notclosed | length) == 0) as $stale
    | (if $stale then
         "STALE_BLOCK\t#\($n)\tstatus: blocked, все Blocked by закрыты (\($d | map("#\(.)") | join(", ")))\tReady"
       else empty end),
      (if $blocked and ($d | length) == 0 then
         "MANUAL_BLOCK\t#\($n)\tstatus: blocked без строк Blocked by — ручная блокировка или мусор\tрешает человек"
       else empty end),
      (if $blocked and ($unknown | length) > 0 then
         "MANUAL_BLOCK\t#\($n)\tBlocked by \($unknown | map("#\(.)") | join(", ")) — нет такой issue\tпоправить тело руками"
       else empty end),
      (if $stale then empty
       elif ($sl | length) > 1 then
         "COLUMN\t#\($n)\tнесколько меток [\($sl | join(", "))], колонка \($col // "нет на доске")\tрешает человек"
       else
         (if ($sl | length) == 1 then column_for($sl[0]) else "Backlog" end) as $want
         | if $col == $want then empty
           else (if ($sl | length) == 0 then "без метки status:" else "метка [\($sl[0])]" end) as $lab
             | if $col == null then "COLUMN\t#\($n)\tнет на доске, \($lab)\t\($want)"
               else "COLUMN\t#\($n)\tколонка \($col) ≠ \($lab)\t\($want)" end end
       end),
      (if ($sl | has("status: in progress") or has("status: in review") or has("status: ready to merge"))
          and (.assignees | length) == 0
          and ($withpr | has($n) | not) and ($withbranch | has($n) | not) and ($wts | has($n) | not) then
         "ABANDONED\t#\($n)\t\($sl | join(", ")): нет исполнителя, ветки, worktree и открытого PR\tрешает человек"
       else empty end)),
    ($closed[] | select(.pr | not)
     | col_or_dash($items[.number | tostring]) as $col
     | select((.labels | length) > 0 or ($col != null and $col != "Done"))
     | "CLOSED\t#\(.number)\tзакрыта, колонка \($col // "нет на доске"), метки [\(.labels | join(", "))]\t\(if $col == null then "снять метки" else "Done" end)")
  ' <<<"$open")

  local total cards
  total=$(jq '[.[] | select(.pull_request | not)] | length' <<<"$open")
  cards=$(jq 'length' <<<"$items")
  [[ -z "$report" ]] || printf '%s\n' "$report"
  echo "sweep: открытых issues $total, карточек на доске $cards; расхождений: $(grep -c . <<<"$report" || true)" \
       "(STALE_BLOCK $(grep -c '^STALE_BLOCK' <<<"$report" || true)," \
       "MANUAL_BLOCK $(grep -c '^MANUAL_BLOCK' <<<"$report" || true)," \
       "COLUMN $(grep -c '^COLUMN' <<<"$report" || true)," \
       "ABANDONED $(grep -c '^ABANDONED' <<<"$report" || true)," \
       "CLOSED $(grep -c '^CLOSED' <<<"$report" || true))"
  (( fix )) || return 0

  # Чинятся только STALE_BLOCK, CLOSED и COLUMN с однозначной колонкой; остальное — решение человека.
  # Перед каждым исправлением метки перечитываются (REST, 0 очков): между снимком и исправлением
  # другая сессия могла сама перевести задачу, и откатывать её переход по старому снимку нельзя.
  local kind num what want now target fixed=0 skipped=0
  while IFS=$'\t' read -r kind num what want; do
    case "$kind" in STALE_BLOCK|COLUMN|CLOSED) ;; *) continue ;; esac
    [[ "$want" == "решает человек" ]] && continue
    now=$(gh api -X GET "repos/$REPO/issues/${num#\#}" \
            | jq -r '[.state] + ([.labels[].name | select(startswith("status:"))] | sort) | join(",")')
    # COLUMN по метке `ready to merge` чинится статусом Ready To Merge — он ставит колонку In Review
    # и оставляет ту же метку; `In Review` снял бы её.
    target=$want
    [[ "$kind" == COLUMN && "$now" == "open,status: ready to merge" ]] && target="Ready To Merge"
    case "$kind:$want" in
      STALE_BLOCK:*)   [[ "$now" == "open,status: blocked" ]] ;;
      COLUMN:Backlog)  [[ "$now" == open ]] ;;
      COLUMN:*)        [[ "$now" == "open,$(label_for "$target")" ]] ;;
      CLOSED:*)        [[ "$now" == closed* ]] ;;
    esac || { echo "$num пропущена: изменилась после сверки ($now)"; skipped=$((skipped + 1)); continue; }
    if [[ "$want" == "снять метки" ]]; then
      strip_status_labels "${num#\#}"
      echo "$num → метки status: * сняты"
    else
      set_status "${num#\#}" "$target"
    fi
    fixed=$((fixed + 1))
  done <<<"$report"
  echo "sweep --fix: исправлено $fixed, пропущено как изменившиеся $skipped"
}

case "${1:-}" in
  status)  set_status "$2" "$3" ;;
  field)   shift; set_board_field "$@" ;;
  unblock) unblock_after "$2" ;;
  list)    shift; list_columns "$@" ;;
  items)   board_items ;;
  sweep)   shift; sweep "$@" ;;
  show)    gh api -X GET "repos/$REPO/issues/$2" \
             | jq -r '"#\(.number) \(.title) [\(.state | ascii_upcase)] \([.labels[].name] | join(","))"' ;;
  *) sed -n '2,9p' "$0"; exit 1 ;;
esac
