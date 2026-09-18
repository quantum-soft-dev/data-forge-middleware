#!/usr/bin/env bash
# Смержить PR и удалить его ветку — единственный шаг merge для /task, /merge и /github-issue-runner.
#   scripts/pr-merge.sh <pr#>            # squash (Rule 1: одна фича = один коммит)
#   scripts/pr-merge.sh <pr#> --rebase   # только когда человек сказал это явно (см. /merge)
#
# Зачем скрипт, а не `gh pr merge --squash --delete-branch` (#332). Запущенный из worktree, gh мержит
# PR на GitHub и ПОСЛЕ этого пытается прибраться локально: переключиться на базу и удалить ветку. База
# занята основной копией (`fatal: 'develop' is already used by worktree`), gh выходит с ненулевым кодом
# — и ветка не удаляется ни локально, ни на remote, а вывод читается как «merge не прошёл». При
# выключенном `deleteBranchOnMerge` ветку больше не уберёт никто. Поэтому здесь:
#   - `gh pr merge` без `--delete-branch` и с `--repo`: без флага gh не трогает локальный git вовсе,
#     а `--repo` отвязывает его от дерева, из которого запущен скрипт;
#   - вердикт — перечитанный через REST PR (`merged`), а не код выхода gh;
#   - ветка удаляется через REST (`DELETE git/refs/heads/<b>`) — это не зависит от локального состояния,
#     и её отсутствие перепроверяется.
# Правило живёт в одном месте по той же причине, что `issue-base.sh` и `board.sh`: пересказ в трёх
# командах расходится молча. Чтения — REST (0 очков GraphQL); `gh pr merge` — единственный GraphQL.
#
# Коды выхода:
#   0 — смержен (сейчас или раньше), ветка удалена (или живёт в форке и не наша);
#   1 — merge не состоялся: PR открыт, текст отказа gh напечатан;
#   2 — отказ ДО любых действий: аргументы, PR закрыт без merge, голова — долгоживущая ветка
#       (`develop`/`main`/`stage`/`release*`/`migration/*`: финальный merge миграции решает её тикет);
#   3 — PR СМЕРЖЕН, а ветка осталась: merge не повторять, удалить ветку отдельно.
set -euo pipefail
REPO=quantum-soft-dev/data-forge-middleware

refuse() { echo "pr-merge.sh: $*" >&2; exit 2; }

(($# >= 1 && $# <= 2)) || refuse "usage: scripts/pr-merge.sh <pr#> [--rebase]"
pr=$1
[[ "$pr" =~ ^[1-9][0-9]*$ ]] || refuse "not a PR number: '$pr'"
method=squash
if (($# == 2)); then
  [[ "$2" == --rebase ]] || refuse "unknown option '$2' (only --rebase; squash is the default)"
  method=rebase
fi

# state, merged, merge sha, head ref, base ref, head repository — одним REST-запросом.
read_pr() {
  gh api "repos/$REPO/pulls/$pr" \
    --jq '[.state, (.merged|tostring), (.merge_commit_sha // "-"), .head.ref, .base.ref, (.head.repo.full_name // "-")] | @tsv'
}

pr_line=$(read_pr) || refuse "cannot read PR #$pr"
IFS=$'\t' read -r state merged sha head base head_repo <<< "$pr_line"

case "$head" in
  develop|main|master|stage|release|release/*|release-*|migration/*)
    refuse "PR #$pr has the long-lived branch '$head' as its head; its own ticket decides how it lands, and it is never deleted" ;;
esac

if [[ "$merged" == true ]]; then
  echo "pr-merge.sh: #$pr is already merged; finishing the branch cleanup only" >&2
else
  [[ "$state" == open ]] || refuse "PR #$pr is $state without a merge"
  rc=0
  out=$(gh pr merge "$pr" "--$method" --repo "$REPO" 2>&1) || rc=$?
  pr_line=$(read_pr) || { echo "$out" >&2; echo "pr-merge.sh: cannot re-read PR #$pr after gh pr merge (exit $rc); check it by hand before retrying" >&2; exit 1; }
  IFS=$'\t' read -r state merged sha head base head_repo <<< "$pr_line"
  if [[ "$merged" != true ]]; then
    echo "$out" >&2
    echo "pr-merge.sh: #$pr was not merged (gh pr merge exit $rc)" >&2
    exit 1
  fi
  if ((rc != 0)); then
    # Ровно случай #332: merge на GitHub прошёл, а gh споткнулся после — на своей локальной уборке.
    echo "$out" >&2
    echo "pr-merge.sh: gh pr merge exited $rc after the merge had landed; its exit code is not the verdict, the PR state is" >&2
  fi
fi

merged_line="#$pr merged into $base as ${sha:0:8}"

if [[ "$head_repo" != "$REPO" ]]; then
  echo "$merged_line; branch $head lives in $head_repo and is not deleted"
  exit 0
fi

# Путь ref: каждый сегмент имени ветки кодируется, слеши остаются слешами.
ref_path=$(jq -rn --arg b "$head" '$b | split("/") | map(@uri) | join("/")')
del_out=$(gh api -X DELETE "repos/$REPO/git/refs/heads/$ref_path" --silent 2>&1) || true

# Удаление проверяется чтением: 422 «Reference does not exist» от DELETE значит «уже удалена», а не
# ошибку, и единственный честный ответ на оба случая — 404 на чтении.
if check_out=$(gh api "repos/$REPO/git/ref/heads/$ref_path" --silent 2>&1); then
  echo "pr-merge.sh: #$pr IS merged into $base, but branch '$head' is still there; do not merge again — delete it: gh api -X DELETE repos/$REPO/git/refs/heads/$ref_path" >&2
  [[ -z "$del_out" ]] || echo "$del_out" >&2
  exit 3
elif [[ "$check_out" != *"HTTP 404"* ]]; then
  echo "pr-merge.sh: #$pr IS merged into $base, but whether branch '$head' is gone could not be read; do not merge again — check it by hand" >&2
  echo "$check_out" >&2
  exit 3
fi

echo "$merged_line; branch $head deleted"
