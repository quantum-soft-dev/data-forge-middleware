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
set -euo pipefail
REPO=quantum-soft-dev/data-forge-middleware
kw=${1:?"ключевые слова"}; shift || true
paths=("$@")

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
for p in "${paths[@]}"; do
  gh pr list -R "$REPO" --state merged --limit 5 --search "\"$p\" merged:>=$since" \
    --json number,title --jq ".[] | \"  PR #\(.number) \(.title)  ← $p\"" || true
done

if ((${#paths[@]})); then
  echo "## Последние коммиты develop по путям"
  git log origin/develop --since="30 days ago" --oneline -- "${paths[@]}" 2>/dev/null | head -10 | sed 's/^/  /' || true
fi
