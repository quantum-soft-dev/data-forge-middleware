#!/usr/bin/env bash
# Поиск похожих issues и недавних решений — перед взятием задачи и перед созданием новой issue.
#   scripts/issue-find.sh "<ключевые слова>" [путь-или-класс ...]
# Выводит: открытые и закрытые issues по словам; открытые issues, чьё ТЕЛО упоминает те же
# файлы/классы; PR, смерженные за 30 дней; последние коммиты develop по путям.
#
# Почему две разные выборки по путям: `--search` ищет по индексу и отвечает «что-то нашлось»,
# а столкновение тикетов надо УВИДЕТЬ поимённо — поэтому второй проход читает тела через --jq
# (CLAUDE.md → «Every follow-up says what it will touch»). Аргумент test() — регулярное выражение:
# точку, скобку и квадратную скобку в имени класса экранируй.
#
# У аргумента-пути три роли с разным написанием (#308): регэксп для тел issues — как передан;
# литерал — тот же аргумент без обратных слешей — для `gh --search`, строки вывода и git. В jq
# литерал идёт только через --arg: интерполированный в строку jq, `ci-cd\.yml` — недопустимый escape,
# jq отказывает, и раздел молча пуст. Git получает `:(icase)*<литерал>*`: голое имя файла не совпадает
# с путём в подкаталоге. «Пусто» должно значить «ничего не нашлось», а не «не смогло искать» —
# IssueFindScriptTest держит это на фикстурах.
set -euo pipefail
REPO=quantum-soft-dev/data-forge-middleware
kw=${1:?"ключевые слова"}; shift || true
paths=("$@")
# Литерал из регэкспа: снять экранирование (`\.` → `.`).
literal() { printf '%s' "$1" | sed -E 's/\\(.)/\1/g'; }

echo "## Issues по словам: $kw"
gh issue list -R "$REPO" --state all --limit 15 --search "$kw" \
  --json number,title,state,labels \
  --jq '.[] | "  #\(.number) [\(.state)] \(.title)  {\([.labels[].name]|join(","))}"' || true

if ((${#paths[@]})); then
  echo "## Открытые issues, чьё тело упоминает: ${paths[*]}"
  for p in "${paths[@]}"; do
    # `gh --jq` не принимает --arg, поэтому фильтруем внешним jq; это по-прежнему разбор JSON,
    # а не grep по одной длинной строке, которую gh печатает.
    gh issue list -R "$REPO" --state open --limit 200 --json number,title,body \
      | jq -r --arg p "$p" '.[] | select(((.title // "") + (.body // "")) | test($p;"i")) | "  #\(.number) \(.title)  ← \($p)"' \
      || echo "  (не разобралось как регэксп: $p — экранируй . ( [ )"
  done
fi

echo "## PR, смерженные за 30 дней (по словам и путям)"
since=$(date -v-30d +%Y-%m-%d 2>/dev/null || date -d '30 days ago' +%Y-%m-%d)
gh pr list -R "$REPO" --state merged --limit 10 --search "$kw merged:>=$since" \
  --json number,title,mergedAt --jq '.[] | "  PR #\(.number) \(.title) (\(.mergedAt[:10]))"' || true
# Guard обязателен: под `set -u` в bash 3.2 — а это /bin/bash на macOS, платформе этого
# репозитория — разворачивание пустого массива "${paths[@]}" само по себе «unbound variable».
# Вызов без путей документирован и является основным сценарием (перед взятием задачи файлы ещё
# не известны), так что без guard'а скрипт падал ровно там, где нужен чаще всего.
if ((${#paths[@]})); then
  for p in "${paths[@]}"; do
    lit=$(literal "$p")
    gh pr list -R "$REPO" --state merged --limit 5 --search "\"$lit\" merged:>=$since" --json number,title \
      | jq -r --arg p "$lit" '.[] | "  PR #\(.number) \(.title)  ← \($p)"' \
      || echo "  (поиск PR не выполнился: $lit)"
  done
fi

if ((${#paths[@]})); then
  echo "## Последние коммиты develop по путям"
  pathspecs=()
  for p in "${paths[@]}"; do pathspecs+=(":(icase)*$(literal "$p")*"); done
  if commits=$(git log origin/develop --since="30 days ago" --oneline -- "${pathspecs[@]}" 2>&1); then
    [[ -z "$commits" ]] || printf '%s\n' "$commits" | head -10 | sed 's/^/  /'
  else
    echo "  (git log не выполнился: $(printf '%s' "$commits" | head -1))"
  fi
fi
