# Точки входа

> HTTP, CLI, CI, UI, очереди и WebSocket — как и чем запускается проект.
> **Обновлять при изменении:** `services/`, `Makefile`, `scripts/`, `.github/workflows/`, `docs/api/openapi.yaml`

## Обзор

Внешняя точка входа одна — Gateway на `:8080`; остальные сервисы доступны напрямую
по своим портам для отладки. Управление — через make-цели и скрипты; проверки — через
три GitHub Actions workflow.

## HTTP API

Публичные REST-эндпоинты (источник — `docs/api/openapi.yaml`, 58 KB; сводка —
`docs/api-spec.md`). Маршрутизация Gateway: `services/gateway/src/main/resources/application.yml`.

| Метод и путь | Тип | Обработчик/маршрут | Вход | Аутентификация |
|---|---|---|---|---|
| `GET /health` | health | Gateway `HealthController` | — | нет |
| `POST /api/transactions` | REST | Gateway -> Switch `/api/internal/route` | `AuthorizationRequest` JSON | нет |
| `GET /api/transactions/search` | REST | Gateway -> Transaction Logger | query-параметры | нет |
| `GET /api/transactions/export` | REST | Gateway -> Transaction Logger | фильтры | нет |
| `POST /api/cards` | REST | Gateway -> Card Management | `CreateCardRequest` | нет |
| `POST /api/cards/generate` | REST | Gateway -> Card Management | `count`, `bins` | нет |
| `GET /api/cards/{pan}` | REST | Gateway -> Card Management | pan | нет |
| `POST /api/cards/{pan}/reserve` | REST | Card Management | `amount`, `rrn` | нет |
| `POST /api/cards/{pan}/rollback` | REST | Card Management | rollback-запрос | нет |
| `POST /api/simulator/terminal/run` | REST | Terminal Simulator | `count`, `scenario`, `tps` | нет |
| `POST /api/simulator/terminal/start` | REST | Terminal Simulator | continuous-запуск | нет |
| `GET /api/simulator/terminal/status` | REST | Terminal Simulator | — | нет |
| `POST /api/simulator/merchant/run` | REST | Merchant Acquirer | `count`, `mccCodes`, `scenario` | нет |
| `GET /api/simulator/merchants` | REST | Merchant Acquirer | — | нет |
| `GET /api/dashboard/stats` | REST | Gateway -> Transaction Logger | — | нет |
| `GET /api/dashboard/recent` | REST | Gateway -> Transaction Logger | `limit` | нет |
| `GET /api/dashboard/charts` | REST | Gateway -> Transaction Logger | фильтры | нет |
| `WS /ws/transactions` | WebSocket | Transaction Logger | подписка | нет |
| `POST /api/internal/route` | internal | Switch | `AuthorizationRequest` | нет |
| `POST /api/internal/authorize` | internal | Authorization | `AuthorizationRequest` | нет |
| `POST /api/internal/log` | internal | Transaction Logger | `Transaction` | нет |

Документация API: Swagger UI `http://localhost:8080/docs`, агрегирует Gateway,
Switch, Logger, Terminal, Merchant и Card Management
(`application.yml:181-196`).

Health-check'и: сервисы отдают `GET /health`, а `bin-lookup` и
`notification-service` — `GET /actuator/health` (`scripts/smoke-test.sh:48-57`).

## CLI и make-цели

`Makefile` — основной интерфейс локального запуска:

| Команда | Действие |
|---|---|
| `make run` | `docker compose up -d` (предварительно копирует `.env`) |
| `make stop` / `make clean` | остановка / остановка с удалением volumes |
| `make build` | пересборка всех образов |
| `make status` / `make logs SERVICE=gateway` | статус / логи |
| `make smoke` | smoke-скрипт с учётом ОС |
| `make test` | `mvn test` + `npm test` |
| `make lint` | `mvn checkstyle:check` + `npm run lint` |
| `make mvn-service SERVICE=gateway` | сборка одного сервиса с зависимостями |
| `make npm-install` / `npm-lint` / `npm-test` / `npm-build` | frontend-цели |

Скрипты:

| Скрипт | Назначение | Вход |
|---|---|---|
| `scripts/smoke-test.sh` | полная авто-приёмка: health, генерация 500 карт, транзакция, поиск, дашборд | `GATEWAY` |
| `scripts/smoke-test.ps1` | то же для Windows | — |
| `scripts/load-smoke.sh` | нагрузочный прогон через `/api/simulator/terminal/run` | `LOAD_COUNT`, default 500 |
| `scripts/autoscaler.sh` | скейлинг `terminal-simulator` по RPS Gateway из Prometheus | профиль `observability` |
| `scripts/gateway-metrics-demo.sh` | демонстрация метрик Gateway | — |

## CI workflow как точки входа

| Workflow | Триггер | Jobs | Секреты |
|---|---|---|---|
| `.github/workflows/ci.yml` | push в любую ветку (кроме `docs/**`, `tz/**`, `**/*.md`, `*.puml`, `*.png`); PR в `main` по путям `services/**` и др. | `base-lint`, `build-common`, `java-services` (matrix 7), `frontend-dashboard` | нет |
| `.github/workflows/practice-run.yml` | `workflow_dispatch` с выбором практики 1, 5 или 6 | `smoke-tests`, `e2e-tests`, `load-smoke` | нет |
| `.github/workflows/practice-check.yml` | `issues: opened, edited, labeled` при label `practice-` | `check` — валидация ссылок в Issue | `github.token` |

## UI

Web Dashboard (`services/dashboard`): React SPA, dev `vite` на `:3000`, production —
nginx. Экраны/виджеты: KPI-карточки, графики Recharts, карта Leaflet, таблица
транзакций, модалка/страница деталей, фильтры, экспорт CSV, тосты, темы.
Real-time — `useWebSocket` к `/ws/transactions`.

## RabbitMQ

| Exchange | Queue | Routing key | Producer | Consumer |
|---|---|---|---|---|
| `smp.transactions` | `transaction-log` | `transaction.log` | Switch | Transaction Logger |
| `smp.transactions.dlx` | `transaction-log-dlq` | `transaction-log` | DLX | — |
| `smp.card-events` | `card-notifications` | `card.*` | Card Management outbox | Notification Service |
| `smp.card-events.dlx` | `card-notifications-dlq` | `card-notifications` | DLX | — |

Management UI: `http://localhost:15672`, логин/пароль `smp`/`smp`
(`docker-compose.yaml:32-33`).

## Связи

- Потоки и сценарии: [03_DATA_FLOW.md](03_DATA_FLOW.md);
  конфигурация: [05_CONFIGURATION.md](05_CONFIGURATION.md);
  эксплуатация: [11_OPERATIONS.md](11_OPERATIONS.md).
