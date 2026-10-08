# Эксплуатация

> Локальный запуск, профили, наблюдаемость, автоскейлинг и типовые операции.
> **Обновлять при изменении:** `docker-compose.yaml`, `Makefile`, `scripts/`, `infra/`, `.env.example`

## Обзор

Всё поднимается одним `docker compose`. Базовый режим — 10 сервисных контейнеров +
PostgreSQL + RabbitMQ; мониторинг и автоскейлер вынесены в профиль `observability`.
Управление — через `Makefile`, диагностика — через `docker compose logs` и health.

## Запуск

```bash
cp .env.example .env          # Windows: Copy-Item .env.example .env
docker compose up -d          # базовый стек
curl http://localhost:8080/health
docker compose --profile observability up -d   # + мониторинг
```

Быстрая проверка: `docker compose up -d && sleep 10 && ./scripts/smoke-test.sh`
(на Windows — `make smoke` -> `scripts/smoke-test.ps1`).

## Runtime-контейнеры

| Контейнер | Образ | Healthcheck |
|---|---|---|
| `smp-postgres` | `postgres:16-alpine` | `pg_isready` |
| `smp-rabbitmq` | `rabbitmq:3.13-management` | `rabbitmq-diagnostics check_running` |
| `smp-gateway` | build `services/gateway/Dockerfile` | `/health` |
| `smp-card-management` | build | `/health` |
| `smp-switch` | build | `/health` |
| `smp-authorization` | build | `/health` |
| `smp-bin-lookup` | build | `/actuator/health` |
| `smp-notification` | build | `/actuator/health` |
| `smp-terminal-simulator` | build | `/health` |
| `smp-merchant-acquirer` | build | `/health` |
| `smp-transaction-logger` | build | `/health` |
| `smp-dashboard` | build | `/health` |
| `smp-prometheus`, `smp-grafana`, `smp-loki`, `smp-promtail`, `autoscaler` | профиль `observability` | — |

Зависимости запуска: сервисы ждут healthy Postgres/RabbitMQ; Gateway зависит от
switch, card-management, transaction-logger; authorization — от Postgres и
card-management (`docker-compose.yaml`).

## Профиль `observability`

- Prometheus `:9090` со scrape-таргетами 7 сервисов по `/actuator/prometheus`
  (`infra/prometheus/prometheus.yml`).
- Grafana `:3001` с provisioned datasources (Prometheus, Loki) и дашбордами:
  system-overview, gateway, switch, authorization, card-management,
  transaction-logger, terminal-simulator, merchant-acquirer.
- Loki + Promtail собирают логи контейнеров.
- Autoscaler (`scripts/autoscaler.sh`) каждые 15 с считает RPS Gateway из
  Prometheus и масштабирует `terminal-simulator` в диапазоне 1..10 контейнеров,
  ориентир — 200 RPS на инстанс.

## Типовые операции

| Задача | Команда |
|---|---|
| Статус | `make status` / `docker compose ps` |
| Логи сервиса | `make logs SERVICE=gateway` |
| Пересборка | `make build` |
| Остановка | `make stop` |
| Остановка с данными | `make clean` |
| Smoke | `make smoke` |
| Сборка одного сервиса | `make mvn-service SERVICE=authorization` |
| Тесты frontend | `make npm-test` |
| Линтеры | `make lint` |
| Сброс при ошибках CI | `docker compose logs --tail=100`, затем `docker compose down -v` |

## Данные и состояние

- Volumes: `pg_data` (БД), `grafana_data`, `loki_data` (`docker-compose.yaml:431-434`).
- `make clean` удаляет volumes — БД и дашборды теряются.
- Flyway-миграции применяются при старте сервисов; notification использует свою
  таблицу истории. Несогласованность default-БД и общих историй — в
  [00_OPEN_QUESTIONS.md](00_OPEN_QUESTIONS.md) Q5, Q6.

## Наблюдаемость приложения

- Gateway экспортирует метрики с histogram-перцентилями 0.5/0.95/0.99 и SLO
  50ms..2s (`services/gateway/src/main/resources/application.yml:37-55`).
- Actuator у Gateway открывает `health`, `info`, `metrics`, `prometheus`.
- Rate limit: token bucket на client IP; circuit breaker: 3 отказа -> размыкание на 10s.
- Graceful shutdown: drain period 30s, stop grace period 35s.

## Связи

- Конфигурация: [05_CONFIGURATION.md](05_CONFIGURATION.md);
  CI: [04_ENTRYPOINTS.md](04_ENTRYPOINTS.md); тесты: [10_TESTING.md](10_TESTING.md).
