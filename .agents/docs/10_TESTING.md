# Тестирование

> Как устроено тестирование объекта СМП и как проверяются студенческие артефакты.
> **Обновлять при изменении:** `services/e2e-tests/`, `scripts/`, `.github/workflows/`, `docs/submission-guide.md`, `docs/checklists.md`

## Обзор

Ключевая особенность: **тесты объекта тестирования удалены** — студенты пишут их
заново в течение семестра (`README.md:21`). В репозитории остались только
E2E-тесты модуля `services/e2e-tests` и инфраструктура проверок. Курс построен как
пирамида: от smoke и тест-дизайна к unit, API/интеграционным, E2E, нагрузочным и
финальной стратегии.

## Уровни по модулям

| Модуль | Уровень | Где артефакт | Как проверяется |
|:---:|---|---|---|
| 1 | smoke | health-check сервисов | `practice-run.yml` job `smoke-tests` -> `scripts/smoke-test.sh` |
| 2 | тест-дизайн | `docs/practice-2/test-design.md` | LLM skill-1 |
| 3 | unit | `services/{service}/src/test/java/...` | `ci.yml` job `java-services` |
| 4 | API + интеграционные | `services/{service}/src/test/java/...` | `ci.yml` + LLM skill-4 |
| 5 | E2E | `docs/practice-5/e2e-report.md` | `practice-run.yml` job `e2e-tests` + LLM skill-2 |
| 6 | CI/CD + нагрузка | `docs/practice-6/test-summary.md` | `practice-run.yml` job `load-smoke` + LLM skill-3 |
| 7 | метрики/покрытие | `docs/practice-7/metrics-report.md` | LLM (скилл не готов) |
| 8 | стратегия | `docs/practice-8/test-strategy.md` | защита |

Источник: `docs/submission-guide.md:30-39`, `docs/checklists.md`.

## Инструменты и фреймворки

| Слой | Инструмент |
|---|---|
| Unit/API Java | `spring-boot-starter-test` (JUnit 5, Mockito, AssertJ) уже подключён во всех сервисных pom; у card-management/merchant-acquirer — `junit-jupiter`, у transaction-logger — `instancio-junit` и mockito javaagent |
| E2E | TestNG 7.10.2, REST Assured 5.4.0, Awaitility 4.2.2, Jackson 2.17.2, Hamcrest, PostgreSQL driver |
| Frontend | Vitest 4, React Testing Library, jsdom |
| Интеграции с БД | контейнеризованная PostgreSQL (по чек-листу артефакта 4) |
| Нагрузка | `scripts/load-smoke.sh` (curl), сценарий и метрики задаёт студент |
| Статический анализ | checkstyle (`services/checkstyle.xml`), ESLint 9, pre-commit |
| Отчёты | Allure (модуль 7/8), Test Summary Report (модуль 6) |

## E2E-набор в репозитории

| Тест | ID | Что проверяет | Особенность |
|---|:---:|---|---|
| `BinLookupE2eTest` | — | интеграция Authorization -> Bin Lookup | — |
| `RabbitMQAsyncE2eTest` | TC-22 | Switch -> RabbitMQ -> Logger, eventual consistency через Awaitility | — |
| `RabbitMQAsyncE2eTest` | TC-23 | недоступность RabbitMQ -> rollback APPROVED, `responseCode 96` | требует вручную `docker stop smp-rabbitmq` |
| `NotificationServiceE2eTest` | TC-25 | outbox -> RabbitMQ -> notification, retry после сбоя | требует ручного управления RabbitMQ |

Запуск в CI: `mvn -f services/pom.xml -pl e2e-tests -am test -DskipE2eTests=false`
(`.github/workflows/practice-run.yml:174-175`). Из-за ручных предусловий TC-23/25
не полностью воспроизводимы в автоматическом прогоне — см. Q7, Q8 в
[00_OPEN_QUESTIONS.md](00_OPEN_QUESTIONS.md).

## Smoke-приёмка

`scripts/smoke-test.sh` (и `.ps1` для Windows) проверяет
(`scripts/smoke-test.sh`):

1. health всех сервисов + RabbitMQ UI + Dashboard;
2. генерацию 500 тестовых карт;
3. одиночную транзакцию (APPROVED или обоснованный DECLINED);
4. Bin Lookup `GET /api/bin/400000` -> `issuerId=ISS001`;
5. Notification Service `GET /api/notifications` -> 200;
6. симулятор терминалов: 50 транзакций, сценарий `mixed`;
7. поиск транзакций и статистику дашборда;
8. финал `🎉 ALL CHECKS PASSED`.

## Нагрузочное тестирование

- Локально: `LOAD_COUNT=500 ./scripts/load-smoke.sh`; метрики p50/p95/p99,
  throughput, error rate собирает студент.
- В CI: тот же скрипт с `LOAD_COUNT=10` — только smoke-проверка, что скрипт не
  сломан; отсутствие скрипта не фейлит билд
  (`.github/workflows/practice-run.yml:227-235`).
- Причина: GitHub-раннеры 2 ядра / 7 GB не дают стабильных метрик
  (`docs/submission-guide.md:108`).

## Quality gates

| Gate | Где | Что проверяет |
|---|---|---|
| `base-lint` | `ci.yml` | pre-commit: YAML/JSON, trailing whitespace, EOF, line endings, merge conflicts, private keys |
| `build-common` | `ci.yml` | сборка `common`, от которого зависят сервисы |
| `java-services` | `ci.yml` | checkstyle + install по matrix 7 сервисов, тесты пока `-DskipTests` |
| `frontend-dashboard` | `ci.yml` | ESLint `--max-warnings 0` + `vite build` |
| smoke/e2e/load | `practice-run.yml` | ручной запуск на момент сдачи |

## Проверка студенческих работ

1. Лёгкий CI в репозитории студента (push).
2. Тяжёлый прогон вручную (практики 1, 5, 6).
3. Issue в эталонном репозитории с label `practice-N`; `practice-check.yml`
   валидирует наличие ссылок на репозиторий и конкретный Actions run.
4. Текстовые практики — LLM-проверка по рубрике; JSON-вердикт публикует
   преподаватель. Скиллы LLM лежат вне репозитория (Q2).

## Связи

- Пайплайн сдачи: [03_DATA_FLOW.md](03_DATA_FLOW.md); CI: [04_ENTRYPOINTS.md](04_ENTRYPOINTS.md);
  чек-листы: `docs/checklists.md`; риски: [00_OPEN_QUESTIONS.md](00_OPEN_QUESTIONS.md).
