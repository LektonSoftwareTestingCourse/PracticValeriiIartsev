# Обзор проекта Practic / СМП

> Что это за репозиторий, зачем он, из чего состоит.
> **Обновлять при изменении:** `README.md`, `services/`, `docs/`, `docker-compose.yaml`, `tz/`

## Обзор

`Practic` — эталонный репозиторий практикума курса «Тестирование ПО». Его объект
тестирования — **СМП, Симулятор процессингового центра**: микросервисная модель
банковского процессинга, эмулирующая путь карточной транзакции от POS-терминала до
авторизации эмитентом и обратно (`README.md:1-23`).

Для Work! Hub спутник имеет роль `content` и назначение «Контент: практикумы»:
здесь лежат и учебные материалы (ТЗ, guides, чек-листы), и готовый стенд-объект
тестирования, и CI-пайплайн сдачи. Ключевая педагогическая установка:
**стенд уже реализован, студенты пишут тесты** — все тесты из `services/` удалены
(`README.md:21`).

## Стек

| Слой | Технология | Evidence |
|---|---|---|
| Язык сервисов | Java 21 | `services/pom.xml:28` |
| Фреймворк | Spring Boot 3.4.1 | `services/pom.xml:13` |
| Сборка | Maven, многомодульный `processing-platform` | `services/pom.xml:19-22` |
| API-документация | springdoc-openapi 2.7.0, Swagger UI `/docs` | `services/gateway/src/main/resources/application.yml:181-196` |
| Frontend | React 18 + TypeScript + Vite + Tailwind + Recharts | `services/dashboard/package.json` |
| БД | PostgreSQL 16 | `docker-compose.yaml:5` |
| Брокер | RabbitMQ 3.13 (management) | `docker-compose.yaml:25` |
| Оркестрация | Docker Compose, профиль `observability` | `docker-compose.yaml` |
| Наблюдаемость | Prometheus, Grafana, Loki, Promtail, autoscaler | `infra/**`, `docker-compose.yaml:368-425` |
| E2E-тесты | TestNG + REST Assured + Awaitility | `services/e2e-tests/pom.xml` |
| CI | GitHub Actions: лёгкий `ci.yml` + тяжёлый `practice-run.yml` | `.github/workflows/` |
| Линтеры | checkstyle, ESLint, pre-commit | `services/checkstyle.xml`, `.pre-commit-config.yaml` |

## Карта каталогов

| Путь | Назначение |
|---|---|
| `README.md` | точка входа, быстрый старт, карта портов, модули 1–8 |
| `docs/` | architecture, api-spec, checklists, submission-guide; `docs/archive/` — устаревшее |
| `docs/api/openapi.yaml` | исходный OpenAPI 3.0 контракт (58 KB) |
| `tz/` | 9 технических заданий по ролям/сервисам |
| `services/` | 9 Java-модулей сервисов + `dashboard` + общий `common` + тестовый `e2e-tests` |
| `starters/` | starter kits на Java, Go, Python, TypeScript |
| `scripts/` | smoke-test.sh/.ps1, load-smoke.sh, autoscaler.sh, gateway-metrics-demo.sh |
| `infra/` | конфиги Prometheus, Grafana, Loki, Promtail |
| `.github/workflows/` | ci.yml, practice-run.yml, practice-check.yml |
| `docker-compose.yaml` | оркестрация сервисов, инфраструктуры и профиля observability |
| `Makefile` | Docker/Maven/npm команды |
| `.env.example` | шаблон переменных окружения (портов) |

## Учебная программа (модули 1–8)

Каждый модуль добавляет уровень тестирования к одному объекту СМП
(`README.md:97-112`, `docs/submission-guide.md:30-39`):

| Модуль | Практика | Артефакт | Проверка |
|:---:|---|---|---|
| 1 | Запуск СМП + smoke | health-check сервисов | CI `smoke-tests` |
| 2 | Тест-дизайн и баг-репорты | `docs/practice-2/test-design.md` | LLM skill-1 |
| 3 | Unit-тесты | `services/{service}/src/test/java/...` | CI `java-services` |
| 4 | API/интеграционные тесты | `services/{service}/src/test/java/...` | CI + LLM skill-4 |
| 5 | E2E-отчёты | `docs/practice-5/e2e-report.md` | CI `e2e-tests` + LLM skill-2 |
| 6 | CI/CD + нагрузочный smoke | `docs/practice-6/test-summary.md` | CI `load-smoke` + LLM skill-3 |
| 7 | Отчётность и метрики | `docs/practice-7/metrics-report.md` | LLM (скилл не готов) |
| 8 | Финальная тестовая стратегия | `docs/practice-8/test-strategy.md` | защита |

## Ключевые принципы объекта тестирования

Из `docs/architecture.md:212-220` и `README.md:13-21`:

1. Только «свои» тестовые карты, внешних BIN нет; ISO 8583 упрощён до JSON.
2. Гибрид: синхронный HTTP (авторизация, резервирование) + асинхронный RabbitMQ
   (логирование, карточные события).
3. Eventual consistency: запись в лог приходит после доставки через очередь.
4. Изоляция через API и очереди, а не через прямую запись в чужие таблицы.
5. Нет антифрода и клиринга — они исключены из архитектуры.
6. Все данные синтетические.

## Связи

- Компоненты и зависимости: [02_ARCHITECTURE.md](02_ARCHITECTURE.md).
- Потоки данных и пайплайн сдачи: [03_DATA_FLOW.md](03_DATA_FLOW.md).
- Точки входа: [04_ENTRYPOINTS.md](04_ENTRYPOINTS.md); конфигурация: [05_CONFIGURATION.md](05_CONFIGURATION.md).
- Модули кода: [06_MODULES.md](06_MODULES.md); домен: [07_DOMAIN_MODEL.md](07_DOMAIN_MODEL.md).
- Тестирование: [10_TESTING.md](10_TESTING.md); эксплуатация: [11_OPERATIONS.md](11_OPERATIONS.md).
- Термины: [12_GLOSSARY.md](12_GLOSSARY.md).
