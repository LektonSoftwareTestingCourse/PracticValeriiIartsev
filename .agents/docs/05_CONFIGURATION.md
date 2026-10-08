# Конфигурация

> Переменные окружения, конфиг-файлы, профили, секреты и внешние зависимости.
> **Обновлять при изменении:** `.env.example`, `docker-compose.yaml`, `services/`

## Обзор

Конфигурация двухуровневая: `docker compose` подставляет порты и креды из `.env`
(шаблон — `.env.example`), а сервисы читают переменные через Spring-плейсхолдеры
`${VAR:default}`. Файл `.env` в репозитории присутствует, но занесён в `.gitignore`
и в рамках discovery **не читался** — только проверено его наличие.

## Переменные окружения

Из `.env.example` (значения по умолчанию) и `docker-compose.yaml`:

| Переменная | Default | Назначение |
|---|---|---|
| `DB_USER` | `smp_user` | пользователь PostgreSQL |
| `DB_PASSWORD` | `smp_password` | пароль PostgreSQL |
| `DB_NAME` | `smp_db` | имя БД |
| `DB_HOST` | `postgres` | хост БД в сети compose |
| `DB_PORT` | `5432` | порт БД |
| `GATEWAY_PORT` | `8080` | внешний порт Gateway |
| `GATEWAY_SHUTDOWN_DRAIN_PERIOD` | `30s` | drain при graceful shutdown |
| `GATEWAY_STOP_GRACE_PERIOD` | `35s` | grace period контейнера |
| `TRANSACTIONS_RATE_LIMIT_CAPACITY` | `100` | емкость token bucket на client IP |
| `TRANSACTIONS_RATE_LIMIT_REFILL_PER_SECOND` | `100` | пополнение токенов в секунду |
| `TRANSACTIONS_RATE_LIMIT_BUCKET_TTL` | `10m` | TTL неактивного bucket |
| `TRANSACTIONS_RATE_LIMIT_MAX_BUCKETS` | `10000` | максимум bucket'ов в памяти |
| `CARD_MGMT_PORT` | `8081` | внешний порт Card Management |
| `SWITCH_PORT` | `8082` | внешний порт Switch |
| `AUTH_PORT` | `8083` | внешний порт Authorization |
| `MERCHANT_PORT` | `8084` | внешний порт Merchant Acquirer |
| `TERMINAL_PORT` | `8085` | внешний порт Terminal Simulator |
| `LOGGER_PORT` | `8088` | внешний порт Transaction Logger |
| `DASHBOARD_PORT` | `3000` | внешний порт Dashboard |
| `PROMETHEUS_PORT` | `9090` | порт Prometheus (профиль observability) |
| `GRAFANA_PORT` | `3001` | порт Grafana |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD` | `admin` / `admin` | креды Grafana |
| `LOKI_PORT` | `3100` | порт Loki |
| `DASHBOARD_ORIGIN` | `http://localhost:3000` | CORS-origin для Logger |
| `RABBITMQ_USER` / `RABBITMQ_PASSWORD` | `smp` / `smp` | креды RabbitMQ |
| `RABBITMQ_AMQP_PORT` / `RABBITMQ_MGMT_PORT` | `5672` / `15672` | порты AMQP и UI |
| `BIN_LOOKUP_PORT` | `8096` | внешний порт Bin Lookup |
| `NOTIFICATION_PORT` | `8097` | внешний порт Notification Service |
| `GHCR_USER` | `local` | namespace образов в compose (`ghcr.io/${GHCR_USER}`) |

Внутренние URL-переменные, задаваемые в compose (не в `.env`): `SWITCH_URL`,
`AUTH_URL`, `CARD_MGMT_URL`, `LOGGER_URL`, `TERMINAL_SIM_URL`, `MERCHANT_SIM_URL`,
`BIN_LOOKUP_URL`, `GATEWAY_URL`, `SPRING_RABBITMQ_*`, `SERVER_PORT`.

## Конфиг-файлы сервисов

| Файл | Особенности |
|---|---|
| `services/gateway/src/main/resources/application.yml` | маршруты Spring Cloud Gateway, rate limit, circuit breaker, graceful shutdown, springdoc |
| `services/card-management/src/main/resources/application.properties` | datasource, Flyway (`out-of-order`, `baseline-on-migrate`), RabbitMQ publisher confirms |
| `services/authorization/.../application.yaml` | datasource, Flyway, URL Bin Lookup и Card Management |
| `services/transaction-logger/.../application.yaml` | datasource `smp_db`, Flyway, CORS через `DASHBOARD_ORIGIN` |
| `services/notification-service/.../application.yml` | отдельная Flyway-таблица `flyway_schema_history_notification`, baseline version 0 |
| `services/merchant-acquirer/.../application.yaml` | datasource `smp_db`, baseline on migrate, Hikari pool 20, batch size 100 |
| `services/switch/.../application.yml` | RabbitMQ, URL Authorization/Logger/Merchant |
| `services/bin-lookup/.../application.yml` | только `server.port: 8080` |
| `services/terminal-simulator/.../application.yml` | URL Gateway и Card Management; datasource закомментирован |
| `services/dashboard/package.json`, `vite.config.ts`, `nginx.conf` | dev-сервер и production-раздача |

## Профили и режимы

- Compose-профиль `observability` включает `prometheus`, `grafana`, `loki`,
  `promtail`, `autoscaler`; базовый запуск их не поднимает
  (`docker compose --profile observability up -d`).
- Maven-профили `services/pom.xml`: `all` (по умолчанию) и по одному профилю на
  сервис + `e2e-tests`; профиль `e2e-tests` переключает `skipE2eTests=false`.
- Флаги приложения: `<skipE2eTests>` (default `true`), `spring.flyway.*`.

## Внешние сервисы и зависимости

| Сервис | Образ/версия | Используется |
|---|---|---|
| PostgreSQL | `postgres:16-alpine` | 4–5 сервисов (см. Q4) |
| RabbitMQ | `rabbitmq:3.13-management` | Switch, Card Management, Logger, Notification |
| Prometheus | `prom/prometheus:v2.55.1` | профиль observability |
| Grafana | `grafana/grafana:11.3.0` | профиль observability |
| Loki | `grafana/loki:3.3.2` | профиль observability |
| Promtail | `grafana/promtail:3.3.2` | профиль observability |
| Docker CLI | `docker:27-cli` | autoscaler |
| CartoDB basemaps | внешний tile-сервер | карта в Dashboard |
| GHCR | `ghcr.io/${GHCR_USER}/smp-*` | образы compose |

## Секреты

- Реальные значения — только в `.env` (gitignored, `**.env.local` тоже).
- `.env.example` содержит учебные дефолты (`smp`/`smp`, `admin`/`admin`).
- В `.env.example` нет токенов/ключей; `pre-commit`-хук `detect-private-key`
  защищает от утечки приватных ключей.
- CI не требует секретов: тяжёлые проверки используют только `github.token`.

## Связи

- Точки входа и порты: [04_ENTRYPOINTS.md](04_ENTRYPOINTS.md);
  эксплуатация: [11_OPERATIONS.md](11_OPERATIONS.md);
  нестыковки конфигов: [00_OPEN_QUESTIONS.md](00_OPEN_QUESTIONS.md) Q6, Q11.
