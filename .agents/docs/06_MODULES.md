# Модули

> Состав Maven-модулей, frontend, starters и e2e — за что каждый отвечает.
> **Обновлять при изменении:** `services/`, `starters/`

## Обзор

`services/` — Maven-реактор `com.processing:processing-platform:1.0.0` с модулями
`common`, 9 сервисов и `e2e-tests`. Frontend (`dashboard`) живёт рядом, но вне
Maven. `starters/` — четыре самостоятельных каркаса на разных языках.

## Maven-модули

| Модуль | Профиль(и) | Зависит от `common` | Назначение |
|---|:---:|:---:|---|
| `common` | `common` | — | общие DTO, аннотации валидации, util'ы, события |
| `gateway` | `gateway` | да | edge-роутинг, rate limit, circuit breaker, логи, shutdown |
| `card-management` | `card-management` | да | карты, генерация, Luhn, reserve/rollback, outbox |
| `switch` | `switch` | да | маршрутизация, вызов Authorization, publish в Logger |
| `authorization` | `authorization` | да | APPROVED/DECLINED, лимиты, RRN/authCode, reversal |
| `terminal-simulator` | `terminal-simulator` | да | POS-сценарии и генерация транзакций |
| `merchant-acquirer` | `merchant-acquirer` | да | мерчанты/терминалы, MCC, комиссия, БД |
| `transaction-logger` | `transaction-logger` | да | приём/поиск транзакций, статистика, WebSocket |
| `bin-lookup` | `bin-lookup` | нет | внешний API обогащения по BIN |
| `notification-service` | `notification-service` | нет | consumer карточных событий |
| `e2e-tests` | `e2e-tests`, входит в `all` | да | TestNG/REST Assured/awaitility E2E |

Профиль `all` активен по умолчанию и собирает все модули; на каждый сервис есть
отдельный профиль, подтягивающий `common` (`services/pom.xml:38-156`).

## Структура сервисного модуля

Типовая раскладка (пример — `authorization`):

| Пакет/каталог | Содержимое |
|---|---|
| `controller` | REST-контроллеры (`AuthController` + `Impl`), health |
| `services` | бизнес-логика (`AuthServiceImpl`, `CleanupService`, `HealthService`) |
| `client` | HTTP-клиенты к другим сервисам (`BinLookupClient`, `CardManagementClient`) |
| `repositories` | доступ к БД (`LimitUsageRepository`) |
| `entities` | JPA-сущности (`LimitUsage`) |
| `dto` | локальные DTO |
| `events` / `listeners` | внутренняя событийная модель и лог-слушатели |
| `exceptions` | доменные исключения |
| `constants` | `DeclineOutcome`, `LogMessages` |
| `configs` | конфигурация приложения |
| `src/main/resources/db/migration` | Flyway-миграции |

У части сервисов встречаются и иные слои: `switch` — `config` + `service`,
`card-management` — `models`/`mappers`/`options`/`retry` (hexagonal-подобная
раскладка портов и адаптеров), `terminal-simulator` — `strategy` + `factory` +
`util`, `transaction-logger` — `websocket`/`specification`/`export`.

## `common`

- DTO: `authorization/*` (AuthorizationRequest/Response, Rollback*),
  `cardmanagement/*` (CardModel, CreateCardRequest, ReserveRequest, Generate*),
  `terminalsimulator/*` (TerminalRun*, TerminalScenario, TerminalType),
  `transactionlogger/*` (TransactionRequest/Response/Status/StoredResponse),
  `ErrorResponse`, `ServiceUnavailableResponse`.
- Кастомная валидация: `@Bin`, `@Pan`, `@Rrn`, `@IssuerId`, `@ExactSize`,
  `@DigitsOnly`, `@NotNegative` + валидаторы.
- Утилиты: `MaskPan`, событийная модель `Event`/`EventListener`/`EventNotifier`.

## Frontend

`services/dashboard` — React 18 + TypeScript + Vite 8 + Tailwind 3 + Recharts +
React Router 7 + Leaflet. Скрипты: `dev`, `build` (`tsc -b && vite build`), `lint`,
`test` (vitest). Структура: `api/`, `components/` (~22 компонента), `contexts/`,
`hooks/` (~7 хуков), `utils/`, `types/`, `mockData.ts`.

## Starters

Четыре независимых каркаса «health-check сервис»:

| Каркас | Файлы | Стек |
|---|---|---|
| `starters/java` | `pom.xml`, `Application`, `HealthController`, `HealthResponse` | Spring Boot, Java |
| `starters/go` | `go.mod`, `cmd/main.go`, `Dockerfile` | Go |
| `starters/python` | `main.py`, `requirements.txt`, `Dockerfile` | Python |
| `starters/typescript` | `vite.config.ts`, `src/App.tsx`, `nginx.conf` | React + TS + Tailwind |

Назначение в текущей модели курса не подтверждено — см. Q9 в
[00_OPEN_QUESTIONS.md](00_OPEN_QUESTIONS.md).

## E2E-модуль

`services/e2e-tests` — не приложение, а тестовый модуль: TestNG 7.10.2, REST
Assured 5.4.0, Awaitility 4.2.2, Jackson 2.17.2. Тесты:
`BinLookupE2eTest`, `NotificationServiceE2eTest` (TC-25), `RabbitMQAsyncE2eTest`
(TC-22, TC-23). Подробнее — [10_TESTING.md](10_TESTING.md).

## Конвенции

- Пакеты: `com.processing.<service>`; у switch — `com.processing.config` и
  `com.processing.service` (без префикса switch).
- Интерфейс + `Impl` для ключевых сервисов и контроллеров.
- Lombok, checkstyle (fail on violation), `services/checkstyle.xml`.
- Dockerfile каждого сервиса — multi-stage Maven -> JRE alpine.

## Связи

- Архитектура: [02_ARCHITECTURE.md](02_ARCHITECTURE.md);
  домен: [07_DOMAIN_MODEL.md](07_DOMAIN_MODEL.md); тестирование: [10_TESTING.md](10_TESTING.md).
