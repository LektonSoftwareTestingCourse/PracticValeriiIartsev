# Practic / СМП — индекс для агентов

Эталонный репозиторий практикума «Тестирование ПО»: объект тестирования — СМП,
Симулятор процессингового центра (Java/Spring Boot микросервисы + React + PostgreSQL
+ RabbitMQ). Стенд реализован; студенты пишут тесты и сдают артефакты через Issue.

**Приоритет истины: код > `.agents/docs/` > прочие `.md`.** README и `docs/*.md` —
гипотезы, требующие проверки кодом.

## Документация для агентов

- [.agents/docs/00_OPEN_QUESTIONS.md](.agents/docs/00_OPEN_QUESTIONS.md) — открытые вопросы и противоречия
- [.agents/docs/01_PROJECT_OVERVIEW.md](.agents/docs/01_PROJECT_OVERVIEW.md) — назначение, стек, карта каталогов, модули курса
- [.agents/docs/02_ARCHITECTURE.md](.agents/docs/02_ARCHITECTURE.md) — компоненты, зависимости, границы, Mermaid
- [.agents/docs/03_DATA_FLOW.md](.agents/docs/03_DATA_FLOW.md) — авторизация, async-логирование, outbox, пайплайн сдачи
- [.agents/docs/04_ENTRYPOINTS.md](.agents/docs/04_ENTRYPOINTS.md) — HTTP API, CLI/make, CI workflow, UI, очереди
- [.agents/docs/05_CONFIGURATION.md](.agents/docs/05_CONFIGURATION.md) — env vars, конфиг-файлы, профили, секреты
- [.agents/docs/06_MODULES.md](.agents/docs/06_MODULES.md) — Maven-модули, frontend, starters, e2e
- [.agents/docs/07_DOMAIN_MODEL.md](.agents/docs/07_DOMAIN_MODEL.md) — сущности, ISO 8583, коды ответа
- [.agents/docs/10_TESTING.md](.agents/docs/10_TESTING.md) — уровни тестов, e2e, smoke, quality gates
- [.agents/docs/11_OPERATIONS.md](.agents/docs/11_OPERATIONS.md) — запуск, observability, autoscaler, операции
- [.agents/docs/12_GLOSSARY.md](.agents/docs/12_GLOSSARY.md) — термины процессинга и курса

## Правила работы в этом репозитории

- Не хранить секреты в Git: реальные значения только в `.env` (gitignored);
  `.env` не читать — проверять только наличие.
- Изменения тестового объекта — через Maven-модули и профили `services/pom.xml`;
  стиль Java контролируется checkstyle (fail on violation).
- Текстовые артефакты практик размещать по путям из
  [docs/submission-guide.md](docs/submission-guide.md).
- Перед коммитом: `make lint` и `make test` (или соответствующие Maven/npm цели).

## Быстрые ориентиры

- Точка входа: Gateway `http://localhost:8080`, Swagger `/docs`, Dashboard `:3000`.
- Запуск: `cp .env.example .env`, `docker compose up -d`, `make smoke`.
- ТЗ по сервисам: `tz/01-devops.md` .. `tz/09-web-dashboard.md`.
- Архитектура объекта: `docs/architecture.md`; API-контракт: `docs/api/openapi.yaml`.
