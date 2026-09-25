#!/usr/bin/env bash
# Волна — до трёх задач параллельно (CLAUDE.md → «Running several issues at once», команда /wave).
#   scripts/wave.sh plan [N]                        # расчёт волны из ≤ N (1–3, по умолчанию 3) задач; ничего не меняет
#   scripts/wave.sh begin [--size N] <n>…           # открыть журнал волны размера N; отказ, если есть незакрытый
#   scripts/wave.sh refill                          # расчёт добора на освободившиеся слоты; ничего не меняет
#   scripts/wave.sh add <n>…                        # дописать задачи добора в журнал
#   scripts/wave.sh mark <n> <колонка> [pr] [заметка] # записать в журнал, в какой колонке задача ДОЛЖНА стоять
#   scripts/wave.sh status                          # журнал текущей волны
#   scripts/wave.sh verify                          # сверка журнала с доской, метками, состоянием issue и PR
#   scripts/wave.sh end [--interrupted]             # закрыть журнал (в архив)
#   scripts/wave.sh budget                          # остаток GraphQL-очков (1 очко)
#   scripts/wave.sh health [--tasks-dir D] [--kill] # зависшие процессы, осиротевшие серверы, застрявшие задачи
#
# Перенесено из zmanly (`/wave`, #216/#220/#265/#218 там) и приведено к правилам этого репозитория:
#   - потолок волны — ТРИ, а не четыре: три параллельных ревью и три очереди CI — предел, записанный в
#     CLAUDE.md для /github-issue-runner, и волна его не расширяет;
#   - пул — колонки `Backlog` и `Ready` доски 16, майлстоун не обязателен (CLAUDE.md: «any milestone»);
#   - приоритет — метка `priority: high|medium|low`, Size — строка `Size: <XS|S|M|L|XL>` в теле (нет —
#     считается M);
#   - пересечение считается по разделу «Что тронет» тела (формы `.github/ISSUE_TEMPLATE/`), а не по
#     меткам модулей — их здесь нет. Ключи: имя каждого файла из раздела (без накопительных `docs/`,
#     `CLAUDE.md`, `AGENTS.md`, любого `README.md`, `specs/**/tasks.md`), плюс три коллизии, которых git не видит:
#     `flyway` (номер миграции — одно пространство на все ветки), `specs` (номер каталога `specs/NNN-*`),
#     `proto` (номера полей `delta-ingestion.proto`); плюс `stand` — `frontend/`, `docker-compose*`,
#     `local-dev/`: живой стенд один на машину (`nonconcurrent`, фиксированные 8080/5432/4566/3000).
#     Нет раздела — ключ `files?`: такие тикеты (старше правила #216) не идут в одну волну друг с другом,
#     и координатор до `begin` проверяет пересечение grep-ом (молчание тикета — не «пересечений нет»);
#   - база тикета — `scripts/issue-base.sh` по телу со stdin (0 очков); отказ скрипта — задача не берётся.
#
# Журнал — источник истины координатора о том, где задачи волны должны стоять. Он живёт в
# .claude/wave/ ОСНОВНОЙ рабочей копии (путь считается от общего .git, поэтому из worktree — тот же файл;
# DFM_WAVE_DIR переопределяет каталог — для тестов) и в git не попадает. Шапка —
# `# wave <время> size=<N>`. Незакрытый журнал = волна идёт или прервана: новая не начинается, пока
# человек не выберет «продолжить» (/wave resume) или «закрыть» (end --interrupted).
#
# Добор: слот занимает строка журнала в `In Progress`/`In Review` (и ещё не взятая строка begin/add);
# `Done`, `Blocked` или вернувшаяся в `Ready` его освобождает. `refill` выбирает на свободные слоты по тем
# же правилам, что `plan`, и не берёт задачи, уже бывшие в журнале, — в том числе новые issues
# исполнителей: они ждут следующей волны.
#
# Стоимость GraphQL: plan, refill и verify — `board.sh items` (1 очко на 100 карточек) + 1 на остаток
# бюджета; всё остальное — REST (0 очков). `gh api rate_limit` для GraphQL не годится: в zmanly он
# показывал `used: 0`, когда `rateLimit` уже списал 41 очко, поэтому бюджет читается запросом
# `rateLimit` самого GraphQL.
#
# Только bash 3.2 и jq, как board.sh.
set -euo pipefail
REPO=quantum-soft-dev/data-forge-middleware
HERE=$(cd "$(dirname "$0")" && pwd)
BOARD="$HERE/board.sh"
BASE="$HERE/issue-base.sh"
# Абсолютный путь обязателен: в основной копии `--git-common-dir` без него относителен (`.git`).
MAIN_ROOT=${DFM_MAIN_ROOT:-$(dirname "$(git -C "$HERE" rev-parse --path-format=absolute --git-common-dir)")}
WAVE_DIR="${DFM_WAVE_DIR:-$MAIN_ROOT/.claude/wave}"
LEDGER="$WAVE_DIR/current.tsv"
MAX_WAVE=3
MIN_BUDGET="${DFM_WAVE_MIN_BUDGET:-1000}"
COLUMNS="Backlog|Ready|In Progress|Blocked|In Review|Done"

fail() { echo "wave.sh: $*" >&2; exit 1; }

budget() { gh api graphql -f query='{rateLimit{remaining}}' | jq -r .data.rateLimit.remaining; }

# REST, все страницы одним массивом.
rest_all() { gh api -X GET "$1" --paginate | jq -s 'add // []'; }

# Номера задач, у которых есть worktree: `.claude/worktrees/<n>-…` (/task, /wave) или
# `.worktrees/<n>-…` (/github-issue вне Conductor).
worktree_numbers() {
  { git -C "$MAIN_ROOT" worktree list --porcelain 2>/dev/null || true; } \
    | sed -n 's#^worktree .*worktrees/\([0-9][0-9]*\)-.*#\1#p' | sort -u
}

# mkdir атомарен: две сессии не откроют две волны и не допишут журнал одновременно.
lock() { mkdir "$WAVE_DIR/lock" 2>/dev/null || fail "журнал волны занят другой сессией ($WAVE_DIR/lock)"; }
unlock() { rmdir "$WAVE_DIR/lock"; }

check_budget() {
  local left
  left=$(budget)
  echo "Бюджет GraphQL: осталось $left (порог волны $MIN_BUDGET)"
  if (( left < MIN_BUDGET )); then
    echo "$1: мало GraphQL-очков — подождать сброса часа."
    exit 4
  fi
}

ledger_numbers() {
  awk -F'\t' '/^#/ || $1 == "" { next } { print $1 }' "$LEDGER" | jq -R 'tonumber' | jq -s .
}

ledger_size() {
  local s
  s=$(sed -n '1s/.*size=\([0-9][0-9]*\).*/\1/p' "$LEDGER")
  echo "${s:-$MAX_WAVE}"
}

# Строки, занимающие слот: In Progress, In Review и ещё не взятые (Ready с заметкой ровно «план»/«добор»).
ledger_busy_numbers() {
  awk -F'\t' '/^#/ { next }
    $2 == "In Progress" || $2 == "In Review" || ($2 == "Ready" && ($4 == "план" || $4 == "добор")) { print $1 }' "$LEDGER"
}
ledger_busy() { ledger_busy_numbers | awk 'END { print NR }'; }

cmd_plan() {
  local n=${1:-$MAX_WAVE}
  [[ "$n" =~ ^[0-9]+$ ]] || fail "plan: N — число 1–$MAX_WAVE"
  (( n >= 1 )) || n=1
  if (( n > MAX_WAVE )); then
    echo "Волна шире $MAX_WAVE не берётся (CLAUDE.md — жёсткий потолок); считаю на $MAX_WAVE."
    n=$MAX_WAVE
  fi

  if [[ -f "$LEDGER" ]]; then
    echo "НЕЗАВЕРШЁННАЯ ВОЛНА — новая не начинается:"
    cmd_status
    echo "Решение человека: /wave resume (продолжить) или scripts/wave.sh end --interrupted (закрыть)."
    exit 3
  fi

  check_budget "Волна не запускается"
  select_tasks "$n" '[]' '[]'
}

cmd_refill() {
  [[ -f "$LEDGER" ]] || fail "refill: нет открытой волны — сначала plan и begin"
  local size busy free
  size=$(ledger_size); busy=$(ledger_busy); free=$(( size - busy ))
  echo "Волна: размер $size, в работе $busy, свободных слотов $(( free > 0 ? free : 0 ))"
  if (( free <= 0 )); then
    echo "Добора нет: свободных слотов нет."
    return 0
  fi
  check_budget "Добор не делается"
  select_tasks "$free" "$(ledger_numbers)" "$(ledger_busy_numbers | jq -R 'tonumber' | jq -s .)"
}

# База каждой открытой issue по её телу: {"<n>": "develop" | "migration/…" | "!<отказ>"}.
bases_of() {
  local issues=$1 n out
  printf '{'
  local first=1
  for n in $(jq -r '.[] | select(.pull_request | not) | .number' <<<"$issues"); do
    if out=$(jq -r --argjson n "$n" '.[] | select(.number == $n) | .body // ""' <<<"$issues" | "$BASE" - 2>&1); then :
    else out="!$(tr '\n' ' ' <<<"$out")"; fi
    (( first )) || printf ','
    first=0
    jq -n --arg k "$n" --arg v "$out" '{($k): $v}' | jq -c . | sed 's/^{//; s/}$//'
  done
  printf '}'
}

# Общий расчёт plan и refill: ≤ want задач без пересечений по ключам. exclude — номера, которые не
# брать (для refill — все номера журнала); held — номера, чьи ключи заняты вдобавок к карточкам
# In Progress/In Review (для refill — строки журнала, занимающие слот).
select_tasks() {
  local n=$1 exclude=$2 held=$3
  local issues prs items wts bases
  issues=$(rest_all "repos/$REPO/issues?state=open&per_page=100")
  prs=$(rest_all "repos/$REPO/pulls?state=open&per_page=100")
  items=$("$BOARD" items | jq -R 'split("\t") | {n: (.[0] | ltrimstr("#") | tonumber), column: .[1], state: .[2]}' | jq -s .)
  wts=$(worktree_numbers | jq -R 'tonumber' | jq -s .)
  bases=$(bases_of "$issues")

  jq -r --argjson want "$n" --argjson prs "$prs" --argjson items "$items" --argjson wts "$wts" \
    --argjson exclude "$exclude" --argjson held "$held" --argjson bases "$bases" '
    def has($x): any(.[]; . == $x);
    def body: .body // "";
    def lbls: [.labels[].name];
    def prio: (lbls) as $l
      | if ($l | has("priority: high")) then 1 elif ($l | has("priority: medium")) then 2
        elif ($l | has("priority: low")) then 3 else 4 end;
    def prioname: {"1":"high","2":"medium","3":"low","4":"-"}[prio | tostring];
    def size: [body | scan("Size: (XS|S|M|L|XL)\\b") | .[0]] | first // "?";
    def sizerank: {"XS":1,"S":2,"M":3,"?":3,"L":4,"XL":5}[size];
    def blockers: [body | scan("Blocked by ((?:#[0-9]+(?:, ?)?)+)") | .[0] | scan("[0-9]+") | tonumber] | unique;
    def statuslabel: [lbls[] | select(startswith("status:"))] | join(",");
    # Раздел «Что тронет» — до следующего заголовка; null, если раздела нет.
    def touches: (body | gsub("\r"; "")) as $b
      | ($b | capture("###\\s*Что тронет[^\\n]*\\n(?<s>[\\s\\S]*?)(?:\\n###\\s|$)") | .s) // null;
    # `[^:\\n]*`, а не `.*?`: во флагах одной строкой («Flyway: нет. specs: нет. proto: да»)
    # ленивый шаблон дотянулся бы до чужого «да».
    def yes($re): test($re + "[^:\\n]*:\\**\\s*(да|yes)"; "i");
    def accumulating: test("^(docs/|CLAUDE\\.md$|AGENTS\\.md$|CHANGELOG\\.md$)") or test("(^|/)README\\.md$")
      or test("^specs/.*tasks\\.md$");
    def wkeys: touches as $t
      | if $t == null then ["files?"]
        else
          ([$t | split("\n")[]
              | select(test("Flyway|specs/NNN|delta-ingestion\\.proto`?:"; "i") | not)
              | scan("`([^`\\s]+)`") | .[0]
              | select(test("[/.]")) | ltrimstr("./") | select(accumulating | not)]) as $files
          | ([$files[] | split("/") | last | select(length > 0)]
             + (if ($t | yes("Flyway")) then ["flyway"] else [] end)
             + (if ($t | yes("specs/NNN")) then ["specs"] else [] end)
             + (if ($t | yes("delta-ingestion\\.proto`?")) or ($files | any(test("delta-ingestion\\.proto$"))) then ["proto"] else [] end)
             + (if ($files | any(test("^frontend/|docker-compose|^local-dev/"))) then ["stand"] else [] end))
          | unique
          | if length == 0 then ["files?"] else . end
        end;
    def col($c): $c[.number | tostring] // "-";

    [.[] | select(.pull_request | not)] as $open
    | [.[] | .number] as $openall
    | ($items | map({key: (.n | tostring), value: .column}) | from_entries) as $c
    | ([$prs[] | ((.body // "") | scan("(?i)(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?) #([0-9]+)") | .[0] | tonumber),
                 (.head.ref | scan("/([0-9]+)-") | .[0] | tonumber)] | unique) as $withpr
    | ($open | map({n: .number, b: blockers})) as $deps
    | ($open | map(select(col($c) == "In Progress" or col($c) == "In Review" or (.number as $x | $held | has($x))))) as $busy
    | ([$busy[] | wkeys[] | select(. != "files?")] | unique) as $busykeys
    | ([$busy[] | select(wkeys == ["files?"])] | length > 0) as $busyunknown

    | [$open[]
        | select(col($c) == "Ready" or col($c) == "Backlog" or (lbls | has("status: ready")))
        | . as $i
        | col($c) as $cc
        | ($bases[.number | tostring] // "develop") as $base
        | [blockers[] | select(. as $b | $openall | has($b))] as $openblk
        | {n: .number, title: .title, ms: (.milestone.number // 999), mst: (.milestone.title // "-"),
           p: prio, pn: prioname, s: size, sr: sizerank, keys: wkeys, base: $base,
           unblocks: ([$deps[] | select(.b | has($i.number))] | length),
           reason: (
             if ($exclude | has($i.number)) then "уже была в этой волне — ждёт следующей"
             elif ($cc == "Ready" and statuslabel != "status: ready") or ($cc == "Backlog" and statuslabel != "")
               or ($cc != "Ready" and $cc != "Backlog")
               then "колонка \($cc) ≠ метка [\(statuslabel)] — сверить доску: board.sh sweep"
             elif (lbls | has("findings-inbox")) then "входящие находки, не задача"
             elif ($openblk | length) > 0 then "открыт Blocked by \($openblk | map("#\(.)") | join(", ")) — на доске должна быть Blocked"
             elif (body | test("Нужно решение")) then "нужно решение человека"
             elif (.sub_issues_summary.total // 0) > 0 then "родитель, не задача"
             elif sizerank >= 4 then "Size \(size) больше M — разбить на sub-issues"
             elif ($base | startswith("!")) then "база не читается: \($base | ltrimstr("!"))"
             elif ($wts | has($i.number)) then "есть worktree — незавершённая сессия"
             elif ($withpr | has($i.number)) then "есть открытый PR"
             else null end)}]
    | (map(select(.reason == null)) | sort_by(.ms, .p, -.unblocks, .sr, .n)) as $ok
    | (reduce $ok[] as $x ({pick: [], skip: [], used: [], unknown: $busyunknown};
        . as $st
        | ([$x.keys[] | select(. != "files?") | select(. as $k | $busykeys | has($k))]) as $hitbusy
        | ([$x.keys[] | select(. != "files?") | select(. as $k | $st.used | has($k))]) as $hitwave
        | ($x.keys == ["files?"] and $st.unknown) as $hitunknown
        | if ($st.pick | length) >= $want then .skip += [$x + {reason: "не влезла: мест \($want)"}]
          elif ($hitbusy | length) > 0 then .skip += [$x + {reason: "пересечение с задачей в работе: \($hitbusy | join(","))"}]
          elif ($hitwave | length) > 0 then .skip += [$x + {reason: "пересечение с задачей волны: \($hitwave | join(","))"}]
          elif $hitunknown then .skip += [$x + {reason: "нет раздела «Что тронет», а без него уже есть задача в работе"}]
          else .pick += [$x] | .used += $x.keys | .unknown = (.unknown or ($x.keys == ["files?"])) end)) as $g
    | "Занято (In Progress / In Review и слоты журнала):",
      (if ($busy | length) == 0 then "  —" else ($busy[] | "  #\(.number)\t\(col($c))\t\(wkeys | join(","))\t\(.title)") end),
      "",
      "ВЫБОР (\($g.pick | length) из \($want)):",
      ($g.pick[] | "PICK\t#\(.n)\tbase=\(.base)\tpriority=\(.pn)\t\(.s)\tразблокирует=\(.unblocks)\t\(.keys | join(","))\t\(.title)"),
      "",
      "ОТСЕВ:",
      (($g.skip + map(select(.reason != null))) | sort_by(.n)[] | "SKIP\t#\(.n)\t\(.reason)\t\(.title)")
  ' <<<"$issues"
}

cmd_begin() {
  local size=$MAX_WAVE
  if [[ "${1:-}" == --size ]]; then
    size=${2:-}
    [[ "$size" =~ ^[0-9]+$ ]] && (( size >= 1 && size <= MAX_WAVE )) || fail "begin: --size — число 1–$MAX_WAVE"
    shift 2
  fi
  (($#)) || fail "begin: номера задач волны"
  (( $# <= size )) || fail "begin: задач $# больше размера волны $size"
  mkdir -p "$WAVE_DIR"
  lock
  if [[ -f "$LEDGER" ]]; then unlock; fail "есть незакрытая волна — /wave resume или end --interrupted"; fi
  { echo "# wave $(date -u +%Y-%m-%dT%H:%M:%SZ) size=$size"
    for i in "$@"; do printf '%s\t%s\t%s\t%s\n' "${i#\#}" "Ready" "-" "план"; done
  } >"$LEDGER"
  unlock
  cmd_status
}

cmd_add() {
  (($#)) || fail "add: номера задач добора"
  local i
  for i in "$@"; do [[ "${i#\#}" =~ ^[0-9]+$ ]] || fail "add: номер issue — $i"; done
  [[ -f "$LEDGER" ]] || fail "add: нет открытой волны"
  lock
  if [[ ! -f "$LEDGER" ]]; then unlock; fail "add: нет открытой волны"; fi
  local free=$(( $(ledger_size) - $(ledger_busy) ))
  if (( $# > free )); then unlock; fail "add: задач $# больше свободных слотов ($free)"; fi
  for i in "$@"; do
    if awk -F'\t' -v n="${i#\#}" '$1 == n { f = 1 } END { exit !f }' "$LEDGER"; then
      unlock; fail "add: #${i#\#} уже в журнале этой волны"
    fi
  done
  for i in "$@"; do printf '%s\t%s\t%s\t%s\n' "${i#\#}" "Ready" "-" "добор"; done >>"$LEDGER"
  unlock
  cmd_status
}

cmd_mark() {
  local n=${1#\#} column=${2:-} pr=${3:-} note=${4:-}
  [[ -f "$LEDGER" ]] || fail "нет открытой волны"
  [[ "$n" =~ ^[0-9]+$ ]] || fail "mark: номер issue"
  [[ "$column" =~ ^($COLUMNS)$ ]] || fail "mark: колонка — $COLUMNS"
  pr=${pr#\#}; [[ "$pr" == - ]] && pr=""
  local tmp="$LEDGER.tmp"
  awk -F'\t' -v OFS='\t' -v n="$n" -v c="$column" -v pr="$pr" -v note="$note" '
    /^#/ { print; next }
    $1 == n { if (pr == "") pr = $3; if (note == "") note = $4; print n, c, pr, note; found = 1; next }
    { print }
    END { if (!found) print n, c, (pr == "" ? "-" : pr), (note == "" ? "новая" : note) }
  ' "$LEDGER" >"$tmp" && mv "$tmp" "$LEDGER"
  printf 'журнал: #%s → %s%s\n' "$n" "$column" "${pr:+ (PR #$pr)}"
}

cmd_status() {
  [[ -f "$LEDGER" ]] || { echo "открытой волны нет"; return; }
  awk -F'\t' '/^#/ { print; next } { printf "  #%s\t%s\tPR %s\t%s\n", $1, $2, $3, $4 }' "$LEDGER"
}

# Метки, допустимые для колонки журнала. `In Review` принимает и `status: ready to merge` — карточка
# той же колонки (CLAUDE.md: «Ready To Merge» — метка, не колонка).
labels_ok() {
  case "$1:$2" in
    "Ready:status: ready"|"In Progress:status: in progress"|"Blocked:status: blocked") return 0 ;;
    "In Review:status: in review"|"In Review:status: ready to merge") return 0 ;;
    "Backlog:"|"Done:") return 0 ;;
    *) return 1 ;;
  esac
}

cmd_verify() {
  [[ -f "$LEDGER" ]] || fail "нет открытой волны"
  local items bad=0 n want pr note got state labels assignees prstate merged problems
  items=$("$BOARD" items)
  while IFS=$'\t' read -r n want pr note; do
    [[ "$n" == \#* || -z "$n" ]] && continue
    got=$(awk -F'\t' -v k="#$n" '$1 == k { print $2 }' <<<"$items")
    local data
    if ! data=$(gh api -X GET "repos/$REPO/issues/$n" 2>/dev/null); then
      bad=1; printf 'MISMATCH\t#%s\tissue не читается через REST\n' "$n"; continue
    fi
    state=$(jq -r .state <<<"$data")
    labels=$(jq -r '[.labels[].name | select(startswith("status:"))] | join(",")' <<<"$data")
    assignees=$(jq -r '.assignees | length' <<<"$data")
    problems=""
    [[ -n "$got" ]] || problems+="нет на доске; "
    [[ -z "$got" || "$got" == "$want" ]] || problems+="колонка $got, ожидалась $want; "
    labels_ok "$want" "$labels" || problems+="метка [${labels}] не для $want; "
    if [[ "$want" == Done ]]; then
      [[ "$state" == closed ]] || problems+="issue открыта при Done; "
    else
      [[ "$state" == open ]] || problems+="issue закрыта при $want; "
    fi
    if [[ "$want" == "In Progress" || "$want" == "In Review" ]]; then
      (( assignees > 0 )) || problems+="нет исполнителя; "
    fi
    if [[ "$pr" =~ ^#?[0-9]+$ ]]; then
      local p
      p=$(gh api -X GET "repos/$REPO/pulls/${pr#\#}" 2>/dev/null) || p='{"state":"не найден","merged":false}'
      prstate=$(jq -r .state <<<"$p"); merged=$(jq -r .merged <<<"$p")
      [[ "$want" != Done || "$merged" == true ]] || problems+="Done, но PR #${pr#\#} не смержен; "
      [[ "$want" != "In Review" || "$prstate" == open ]] || problems+="In Review, но PR #${pr#\#} $prstate; "
    elif [[ "$want" == "In Review" ]]; then
      problems+="In Review без PR в журнале; "
    fi
    if [[ -z "$problems" ]]; then
      printf 'OK\t#%s\t%s\n' "$n" "$want"
    else
      bad=1; printf 'MISMATCH\t#%s\t%s\n' "$n" "${problems%; }"
    fi
  done <"$LEDGER"
  return $bad
}

# --- Здоровье сессий (zmanly #265) -----------------------------------------------------------------
# Строки отчёта: <ВИД>\t<pid|#n>\t<что>\t<что предлагается>.
#   STALE_LOOP        фоновая оболочка Claude с until/while/sleep старше порога
#   STALE_WATCH       `gh pr checks … --watch` / `gh run watch` старше порога
#   ORPHAN_SERVER     vite / playwright / клиент gradlew / spring-boot с cwd в worktree, который удалён
#                     или чья issue закрыта
#   LEFTOVER_WORKTREE worktree закрытой issue
#   STALLED           строка журнала In Progress: ни файлов worktree, ни PR, ни транскрипта исполнителя
#                     новее порога
#   CI_RED / CI_SLOW  строка журнала In Review: красные проверки PR / проверка идёт дольше порога
# --kill завершает дерево процессов только у STALE_LOOP, STALE_WATCH и ORPHAN_SERVER. Процессы основной
# копии (живой стенд), демоны Gradle/Kotlin, docker и чужие проекты не трогаются никогда.
# Код 1 — осталось что-то кроме STALLED и CI_* (им нужно решение координатора, а не kill).
HEALTH_MAX_MIN="${DFM_HEALTH_MAX_MIN:-60}"
WORKTREES_DIR="$MAIN_ROOT/.claude/worktrees"

etime_min() {
  awk -v t="$1" 'BEGIN { d = 0; if (index(t, "-")) { split(t, a, "-"); d = a[1]; t = a[2] }
    n = split(t, p, ":"); s = 0; for (i = 1; i <= n; i++) s = s * 60 + p[i]
    print int((d * 86400 + s) / 60) }'
}

health_ps() {
  if [[ -n "${DFM_HEALTH_PS:-}" ]]; then eval "$DFM_HEALTH_PS"
  else ps -U "$(id -u)" -o pid=,ppid=,etime=,command=; fi
}

cwd_of() {
  if [[ -n "${DFM_HEALTH_CWD:-}" ]]; then awk -F'\t' -v p="$1" '$1 == p { print $2; exit }' "$DFM_HEALTH_CWD"
  else { lsof -a -p "$1" -d cwd -Fn </dev/null 2>/dev/null || true; } | sed -n 's/^n//p' | awk 'NR == 1'; fi
}

# Граница `/` обязательна: соседний каталог `<корень>-old` начинается с того же префикса.
under_main() { [[ "$1" == "$MAIN_ROOT" || "$1" == "$MAIN_ROOT"/* ]]; }

ancestors() {
  local p=$$
  while [[ -n "$p" && "$p" != 0 && "$p" != 1 ]]; do
    echo "$p"; p=$(ps -o ppid= -p "$p" 2>/dev/null | tr -d ' ') || p=
  done
}

STATE_CACHE=" "
issue_state() {
  local hit
  hit=$(sed -n "s/.* $1:\([a-z]*\) .*/\1/p" <<<"$STATE_CACHE")
  if [[ -z "$hit" ]]; then
    hit=$(gh api -X GET "repos/$REPO/issues/$1" </dev/null 2>/dev/null | jq -r '.state // "unknown"') || hit=unknown
    STATE_CACHE+="$1:$hit "
  fi
  echo "$hit"
}

kill_tree() {
  local c
  for c in $(pgrep -P "$1" 2>/dev/null); do kill_tree "$c"; done
  kill -TERM "$1" 2>/dev/null || true
}

worktree_issue() {
  local rel seg
  [[ "$1" == "$WORKTREES_DIR"/* ]] || return 0
  rel=${1#"$WORKTREES_DIR"/}; seg=${rel%%/*}
  [[ "${seg%%-*}" =~ ^[0-9]+$ ]] && echo "${seg%%-*}"
  return 0
}

task_active() {
  local n=$1 note=$2 tasks_dir=$3 wt branch upd agent
  wt=$(ls -d "$WORKTREES_DIR/$n"-* 2>/dev/null | awk 'NR == 1') || wt=""
  if [[ -n "$wt" ]]; then
    [[ -n $(find "$wt" \( -name .git -o -name node_modules -o -name build -o -name .gradle -o -name dist \) -prune \
      -o -type f -mmin "-$HEALTH_MAX_MIN" -print -quit 2>/dev/null) ]] && return 0
    branch=$(git -C "$wt" branch --show-current 2>/dev/null) || branch=""
    if [[ -n "$branch" ]]; then
      upd=$(gh api -X GET "repos/$REPO/pulls?state=open&head=${REPO%%/*}:$branch" </dev/null 2>/dev/null \
        | jq -r '.[0].updated_at // empty') || upd=""
      if [[ -n "$upd" ]] && (( ( $(date -u +%s) - $(jq -n --arg t "$upd" '$t | fromdateiso8601') ) / 60 < HEALTH_MAX_MIN )); then
        return 0
      fi
    fi
  fi
  agent=$(sed -n 's/.*agent=\([A-Za-z0-9_-]*\).*/\1/p' <<<"$note")
  if [[ -n "$tasks_dir" && -n "$agent" && -f "$tasks_dir/$agent.output" ]]; then
    [[ -n $(find "$tasks_dir/$agent.output" -mmin "-$HEALTH_MAX_MIN" 2>/dev/null) ]] && return 0
  fi
  return 1
}

cmd_health() {
  local tasks_dir="" kill=0
  while (($#)); do
    case "$1" in
      --tasks-dir) tasks_dir=${2:-}; shift 2 ;;
      --kill) kill=1; shift ;;
      *) fail "health: [--tasks-dir <каталог>] [--kill]" ;;
    esac
  done
  local max=$HEALTH_MAX_MIN slug anc found=0 hard=0
  slug=$(printf '%s' "$MAIN_ROOT" | tr '/' '-')
  anc=" $(ancestors | tr '\n' ' ') "

  report() {
    found=1
    if [[ -n "${5:-}" && $kill == 1 ]]; then
      kill_tree "$5"; printf 'KILLED\t%s\t%s: %s\t-\n' "$2" "$1" "$3"
    else
      printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4"
      case "$1" in STALLED|CI_*) ;; *) hard=1 ;; esac
    fi
  }

  local pid ppid etime cmd min cwd mine n what
  while read -r pid ppid etime cmd <&3; do
    [[ "$pid" =~ ^[0-9]+$ ]] || continue
    [[ "$anc" == *" $pid "* ]] && continue
    case "$cmd" in *GradleDaemon*|*org.gradle.launcher.daemon*|*KotlinCompileDaemon*|*kotlin-daemon*|*kotlin.daemon*|*docker*|*com.docker*) continue ;; esac
    min=$(etime_min "$etime")
    mine=0
    case "$cmd" in
      *"$MAIN_ROOT/"*|*"$MAIN_ROOT "*|*"$MAIN_ROOT'"*|*"$MAIN_ROOT\""*|*"$MAIN_ROOT"|*"/$slug/"*) mine=1 ;;
    esac
    if [[ "$cmd" == *".claude/shell-snapshots"* ]] && grep -qE '(^|[^[:alnum:]_])(until|while)[[:space:]]|sleep[[:space:]]' <<<"$cmd"; then
      (( min >= max )) || continue
      (( mine )) || { cwd=$(cwd_of "$pid") || cwd=""; under_main "$cwd" && mine=1; }
      (( mine )) || continue
      what="цикл ожидания живёт $min мин"
      [[ "$cmd" == *"pgrep -f"* ]] && what+="; pgrep -f находит собственную командную строку — не кончится никогда"
      report STALE_LOOP "$pid" "$what" "--kill" "$pid"
    elif grep -qE 'gh (pr checks .*--watch|run watch)' <<<"$cmd"; then
      (( min >= max )) || continue
      (( mine )) || { cwd=$(cwd_of "$pid") || cwd=""; under_main "$cwd" && mine=1; }
      (( mine )) || continue
      report STALE_WATCH "$pid" "ожидание CI живёт $min мин: ${cmd:0:80}" "--kill; CI — разовым gh pr checks" "$pid"
    elif grep -qE 'vite|playwright|gradle-wrapper\.jar|GradleWrapperMain|spring-boot|bootRun' <<<"$cmd"; then
      cwd=$(cwd_of "$pid") || cwd=""
      n=$(worktree_issue "$cwd")
      [[ -n "$n" ]] || continue
      if ! ls -d "$WORKTREES_DIR/$n"-* >/dev/null 2>&1; then
        report ORPHAN_SERVER "$pid" "сервер в удалённом worktree #$n ($cwd), живёт $min мин" "--kill" "$pid"
      elif [[ $(issue_state "$n") == closed ]]; then
        report ORPHAN_SERVER "$pid" "сервер в worktree закрытой issue #$n ($cwd), живёт $min мин" "--kill" "$pid"
      fi
    fi
  done 3< <(health_ps)

  local d
  for d in "$WORKTREES_DIR"/[0-9]*-*; do
    [[ -d "$d" ]] || continue
    n=$(basename "$d"); n=${n%%-*}
    [[ $(issue_state "$n") == closed ]] || continue
    report LEFTOVER_WORKTREE "#$n" "$d — issue закрыта" "дочистка шага 6: git worktree remove (грязный — в отчёт)"
  done

  if [[ -f "$LEDGER" ]]; then
    local col pr note p sha runs red slow
    while IFS=$'\t' read -r n col pr note <&4; do
      [[ "$n" =~ ^[0-9]+$ ]] || continue
      case "$col" in
        "In Progress")
          task_active "$n" "$note" "$tasks_dir" && continue
          report STALLED "#$n" "нет активности ≥ $max мин: ни файлов worktree, ни PR, ни транскрипта исполнителя" \
            "SendMessage исполнителю «статус?»; второй раз подряд — шаг 4 «исполнитель упал»" ;;
        "In Review")
          [[ "$pr" =~ ^[0-9]+$ ]] || continue
          p=$(gh api -X GET "repos/$REPO/pulls/$pr" </dev/null 2>/dev/null) || continue
          sha=$(jq -r '.head.sha // empty' <<<"$p") || sha=""; [[ -n "$sha" ]] || continue
          runs=$(gh api -X GET "repos/$REPO/commits/$sha/check-runs?per_page=100" </dev/null 2>/dev/null) || continue
          red=$(jq -r '[.check_runs[] | select(.conclusion == "failure" or .conclusion == "timed_out"
            or .conclusion == "cancelled" or .conclusion == "action_required") | .name] | unique | join(",")' <<<"$runs") || red=""
          slow=$(jq -r --argjson now "$(date -u +%s)" --argjson max "$max" '[.check_runs[]
            | select(.status != "completed" and ((.started_at // "") | length) > 0)
            | select(($now - (.started_at | fromdateiso8601)) / 60 >= $max) | .name] | unique | join(",")' <<<"$runs") || slow=""
          [[ -z "$red" ]] || report CI_RED "#$n" "PR #$pr: красные проверки $red" "MODE: FIX исполнителю"
          [[ -z "$slow" ]] || report CI_SLOW "#$n" "PR #$pr: проверки идут ≥ $max мин: $slow" "ссылку на прогон — в отчёт" ;;
      esac
    done 4<"$LEDGER"
  fi

  (( found )) || printf 'OK\t-\tзависших процессов и застрявших задач нет\t-\n'
  return $hard
}

cmd_end() {
  [[ -f "$LEDGER" ]] || fail "нет открытой волны"
  local tag=done
  [[ "${1:-}" == --interrupted ]] && tag=interrupted
  mv "$LEDGER" "$WAVE_DIR/$(date -u +%Y%m%dT%H%M%SZ)-$tag.tsv"
  echo "волна закрыта ($tag)"
}

case "${1:-}" in
  plan)   shift; cmd_plan "$@" ;;
  refill) cmd_refill ;;
  begin)  shift; cmd_begin "$@" ;;
  add)    shift; cmd_add "$@" ;;
  mark)   shift; cmd_mark "$@" ;;
  status) cmd_status ;;
  verify) cmd_verify ;;
  end)    shift; cmd_end "$@" ;;
  budget) budget ;;
  health) shift; cmd_health "$@" ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
