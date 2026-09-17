## Что происходит

Миграция на Spring Boot 4.1 (майлстоун «Sprint Migration — Spring Boot 4.1») ведётся в долгоживущей
ветке **`migration/spring-boot-4.1`**: тикеты #299–#304 мержатся в неё, а в `develop` она
вливается одним финальным тикетом #305. Порядок задан строками `Blocked by`. Сейчас процесс этого
не поддерживает, и из-за трёх разрывов порядок не удержится:

1. **Тикет не закрывается — цепочка встаёт на первом звене.** Default branch — `develop`, а
   `Closes #N` срабатывает только при merge в неё. После merge в ветку миграции issue остаётся
   открытой. `scripts/board.sh unblock` разблокирует только тикеты с **закрытыми** блокерами, а
   `/task` на шаге 1 отказывается брать задачу с открытым `Blocked by #N`.
2. **У PR в ветку миграции нет CI.** В `.github/workflows/ci-cd.yml` `on.pull_request.branches` —
   только `main`, `release`, `develop`: `backend-test` и `frontend-test` не запустятся. Защита с
   required `backend-test` стоит только на `develop`.
3. **Команды жёстко целятся в `develop`.** `/task`: worktree от `origin/develop`,
   `git pull --rebase origin develop`, «PR в `develop`». `/github-issue`: то же плюс шаг
   синхронизации. `/merge`: `git fetch origin develop`, `git merge origin/develop`.
   `/github-issue-runner`: стоп по красному CI на `develop`. Исполнитель тикета миграции откроет PR
   в `develop`.

## Что решить

Машиночитаемая строка **в начале тела issue**, рядом с `Blocked by` и в том же стиле: слова «Base
branch:» и имя ветки в обратных кавычках (так оформлены #299–#304). Читается только строка, которая
стоит **до первого заголовка и вне блока кода**. Иначе пример в описании — как в этом тикете —
ошибочно переключит базу; этот тикет сам мержится в `develop`.

Строки нет — база `develop`, всё как сейчас. Строка есть — везде, где команды говорят `develop` про
ветвление, синхронизацию, базу PR и цель merge, подставляется указанная ветка:

- **`/task`:** worktree от `origin/<base>`, rebase от `origin/<base>`,
  `gh pr create --base <base>`. На шаге 6 после merge — **явно** `gh issue close` с комментарием
  «merged into `<base>` by #PR», снятие всех `status:*`, `board.sh status <n> Done` и
  `board.sh unblock <n>`: `Closes` здесь не сработает.
- **`/github-issue`:** то же для ветвления, синхронизации и базы PR.
- **`/merge`:** база берётся из `baseRefName` PR, а не константой. При `baseRefName != develop`
  issue закрывается явно.
- **`/github-issue-runner`:** стоп по красному CI — на базовой ветке тикета.
- **`CLAUDE.md`** (Rule 1, «Working from a GitHub issue») и сжатое зеркало в **`AGENTS.md`:**
  исключение для миграции, слишком большой для одного PR. Она живёт в `migration/<name>`, её тикеты
  несут `Base branch:`, в `develop` ветка вливается отдельным тикетом со своим решением о способе
  merge.
- **`ci-cd.yml`:** добавить `'migration/**'` в `on.pull_request.branches` и `on.push.branches`;
  `run_full_pipeline` для неё остаётся `false`.
- **Шаблоны `.github/ISSUE_TEMPLATE/task.yml` и `bug.yml`,** если к моменту взятия они уже в
  `develop`: необязательное поле «Base branch».

Отвергнуто:
- **Указать базу только текстом в тикете, без поддержки в командах.** Исполнитель следует шагам
  команды, а не абзацу тикета, и разрыв 1 всё равно останавливает цепочку.
- **Мержить тикеты миграции сразу в `develop`.** Это противоречит решению вести миграцию в отдельной
  ветке.

## Definition of Done

- [ ] Тикет с `Base branch:` проходит `/task` до конца: worktree от ветки миграции, PR с
      `base = migration/spring-boot-4.1`, после merge issue закрыта, карточка в `Done`, тикеты с
      закрытыми блокерами переведены `board.sh unblock` в `Ready`. Доказано разбором шагов в PR или
      сухим прогоном на одноразовых ветке и тикете, которые потом удалены.
- [ ] Тикет без `Base branch:` ведёт себя как сейчас (база `develop`, `Closes #N`).
- [ ] Строка `Base branch:` в блоке кода или после первого заголовка базу не меняет (проверено на этом тикете).
- [ ] PR в `migration/**` получает `backend-test` и `frontend-test`.
- [ ] **Человек:** защита `migration/**` (required status check `backend-test`), как у `develop`.
      Это настройка репозитория, агент её не меняет.
- [ ] После merge в `develop`: `origin/develop` влит в `migration/spring-boot-4.1`, чтобы CI-триггер
      оказался в ветке миграции.
- [ ] Guard-тесты `com.bitbi.dfm.documentation.*` зелёные.

## Что тронет

**Файлы:** `.claude/commands/task.md`, `.claude/commands/github-issue.md`,
`.claude/commands/merge.md`, `.claude/commands/github-issue-runner.md`,
`.github/workflows/ci-cd.yml`, `CLAUDE.md`, `AGENTS.md`; при наличии —
`.github/ISSUE_TEMPLATE/task.yml` и `bug.yml`. `scripts/board.sh` не меняется: `unblock` уже работает
от закрытых блокеров.

**Flyway-миграция:** нет
**Каталог `specs/NNN-*`:** нет
**`delta-ingestion.proto`:** нет

## Открытые тикеты в тех же файлах

none found: открыты только #296 и #297, они в `delta/application` и `application.yml`.

## Смежное, сюда не входит

Сама миграция: #299–#305. Все они заблокированы этим тикетом, напрямую или транзитивно.

