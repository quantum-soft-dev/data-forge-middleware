#!/usr/bin/env bash
# Базовая ветка issue: от неё ветвится работа, с ней синхронизируется, в неё открывается PR и в неё
# мержится (issue #298).
#   scripts/issue-base.sh <issue#>   # тело берётся из GitHub
#   scripts/issue-base.sh -          # тело со stdin (так его гоняет IssueBaseBranchScriptTest)
# Печатает одну строку — `develop` или `migration/<name>` — и выходит с 0.
#
# Базу меняет только строка ровно такого вида, с первой колонки:
#   Base branch: `migration/<name>`
# и только если она стоит ДО первого заголовка и ВНЕ блока кода (``` / ~~~ или отступ от 4 пробелов).
# Иначе пример в прозе тикета — как в самом #298 — молча переключил бы базу тикету, которому место в
# `develop`. Строки нет — `develop`.
#
# Молчание в другую сторону так же опасно: тикет миграции, чью строку не прочли, откроет PR в
# `develop` — ровно тот разрыв, ради которого #298. Поэтому строка, ПОХОЖАЯ на объявление (другой
# регистр, отступ в 1–3 пробела, без обратных кавычек, с хвостом), вторая такая строка и ветка не
# `develop` / `migration/<name>` — отказ с кодом 2 и пустым stdout. Вызывающая команда на отказе
# останавливается, а не подставляет `develop`.
#
# Правило живёт в одном месте, как id доски в CLAUDE.md: /task, /github-issue и /github-issue-runner
# вызывают этот скрипт, а не пересказывают правило каждая по-своему. /merge базу из тела не читает —
# берёт её из `baseRefName` PR. Заголовки — только ATX (`## …`): шаблоны и тикеты репозитория других
# не используют. Только bash 3.2 — это /bin/bash на macOS: без ${var,,} и ассоциативных массивов.
set -euo pipefail
REPO=quantum-soft-dev/data-forge-middleware

fail() { printf 'issue-base: %s\n' "$*" >&2; exit 2; }

# `x` в конце держит хвостовые переводы строк, которые $(…) иначе срезал бы. Статус подстановки —
# статус присваивания, поэтому сбой `gh` доходит до `||`, а не теряется.
case "${1:-}" in
  -) body=$(cat && printf x) ;;
  ''|*[!0-9]*) fail "нужен номер issue или '-' (тело со stdin), а пришло: '${1:-}'" ;;
  # REST, а не `gh issue view`: GraphQL-бюджет общий на все сессии и кончается первым.
  *) body=$(gh api "repos/$REPO/issues/$1" --jq '.body // ""' && printf x) \
       || fail "не удалось прочитать тело issue #$1" ;;
esac
body=${body%x}

# Регулярки — в переменных: в [[ =~ ]] обратные кавычки иначе пришлось бы экранировать.
open_fence_re='^(```+|~~~+)(.*)$'
close_fence_re='^(`+|~+)[[:space:]]*$'
heading_re='^#{1,6}([[:space:]]|$)'
looks_like_re='^[Bb][Aa][Ss][Ee][[:space:]]*[Bb][Rr][Aa][Nn][Cc][Hh][[:space:]]*:'
declaration_re='^Base branch: `([^`]*)`$'
branch_re='^(develop|migration/[A-Za-z0-9][A-Za-z0-9._-]*)$'

fence=""   # маркер открытого забора; пусто — вне кода
base=""
line_no=0
while IFS= read -r line || [[ -n "$line" ]]; do
  line_no=$((line_no + 1))
  line=${line%$'\r'}
  lead=${line%%[! ]*}
  indent=${#lead}
  text=${line:$indent}

  if [[ -n "$fence" ]]; then
    # Закрывает только маркер того же символа и не короче открывшего, без хвоста.
    if [[ $indent -le 3 && "$text" =~ $close_fence_re ]]; then
      marker=${BASH_REMATCH[1]}
      [[ "${marker:0:1}" == "${fence:0:1}" && ${#marker} -ge ${#fence} ]] && fence=""
    fi
    continue
  fi

  # Отступ от четырёх пробелов или таб — отступной код (или продолжение абзаца): не разметка.
  [[ $indent -gt 3 || "${text:0:1}" == $'\t' ]] && continue

  if [[ "$text" =~ $open_fence_re ]]; then
    marker=${BASH_REMATCH[1]}
    # У забора из обратных кавычек в info-строке обратной кавычки быть не может — иначе это инлайн-код.
    if [[ "${marker:0:1}" != '`' || "${BASH_REMATCH[2]}" != *'`'* ]]; then
      fence=$marker
      continue
    fi
  fi

  [[ "$text" =~ $heading_re ]] && break
  [[ "$text" =~ $looks_like_re ]] || continue

  [[ $indent -eq 0 && "$line" =~ $declaration_re ]] \
    || fail "строка $line_no похожа на объявление базы, но это не точная форма 'Base branch: \`<ветка>\`' с первой колонки: $line"
  declared=${BASH_REMATCH[1]}
  [[ -z "$base" ]] || fail "Base branch объявлена больше одного раза (строка $line_no)"
  base=$declared
  [[ "$base" =~ $branch_re ]] \
    || fail "база '$base' (строка $line_no) — не develop и не migration/<name>; Base branch переключает только на ветку миграции"
done <<< "$body"

printf '%s\n' "${base:-develop}"
