# Открытые вопросы и противоречия

> Неизвестное, противоречия и непроверенные гипотезы по репозиторию `Practic`.
> **Обновлять при изменении:** —

## Обзор

Ниже — вопросы, которые не удалось закрыть по коду и документации. Каждый
пронумерован, содержит evidence и пометку [ФАКТ] (проверено) или [ГИПОТЕЗА] (не
проверялось запуском).

## Детали

### Q1. Отсутствует `docs/e2e-test-plan.md`, на который ссылаются README и чек-листы
- [ФАКТ] `README.md:109` (модуль 7) и `docs/checklists.md:66-72` ссылаются на
  `docs/e2e-test-plan.md`, но в `docs/` его нет — есть только `api-spec.md`,
  `architecture.md`, `checklists.md`, `submission-guide.md` и `docs/archive/**`.
- Вопрос: E2E-план вынесен во внешние материалы, удалён намеренно или еще не создан?

### Q2. Внешние `materials/llm/skills/skill-*.md` не существуют в репозитории
- [ФАКТ] `docs/submission-guide.md:37-39` и `docs/checklists.md:72,82` ссылаются на
  `../../materials/llm/skills/skill-1..4*.md` — в `Practic` каталога `materials/` нет.
- Вопрос: где физически лежат скиллы LLM-проверки и входят ли они в поставку студенту?

### Q3. «11 микросервисов» vs фактическое число контейнеров
- [ФАКТ] `README.md:15` и smoke-скрипт говорят про 11 сервисов. В `docker-compose.yaml`
  сервисных контейнеров 10 (`gateway`, `card-management`, `switch`, `authorization`,
  `bin-lookup`, `notification-service`, `terminal-simulator`, `merchant-acquirer`,
  `transaction-logger`, `dashboard`); 11-м в профиле `observability` идёт `autoscaler`.
- Вопрос: «11» включает autoscaler или подразумевает еще один контейнер/сервис?

### Q4. `docs/architecture.md` занижает число сервисов с доступом к БД
- [ФАКТ] `docs/architecture.md:216` перечисляет 4 сервиса с БД (Card Management,
  Authorization, Transaction Logger, Notification Service). Но `merchant-acquirer`
  тоже имеет `spring.datasource` и Flyway-миграции `V71..V74`
  (`services/merchant-acquirer/src/main/resources/application.yaml`).
- Вопрос: merchant-acquirer осознанно исключён из схемы или документацию надо поправить?

### Q5. Общая Flyway-история в одной БД
- [ФАКТ] Один инстанс PostgreSQL, схема `public`. Миграции: card-management `V5.x`,
  transaction-logger `V1..V2`, authorization `V3.1`, merchant-acquirer `V71..V74`,
  notification `V1` с отдельной таблицей `flyway_schema_history_notification`
  (`services/notification-service/README.md`).
- Вопрос: как card-management, transaction-logger и merchant-acquirer делят
  default-таблицу `flyway_schema_history` без конфликтов версий? Нужна проверка на
  чистой БД. [ГИПОТЕЗА] возможно, помогает `baseline-on-migrate` + `out-of-order`.

### Q6. Несогласованные значения БД по умолчанию
- [ФАКТ] `DB_NAME` в `.env.example:3` = `smp_db`; `authorization`/`notification`
  default `${POSTGRES_DB:postgres}`; `merchant-acquirer`/`transaction-logger` default
  `smp_db`. В compose всегда передаётся `${DB_NAME}`.
- Вопрос: поведение при запуске сервиса без переменных окружения; нужно ли привести defaults.

### Q7. E2E-тесты требуют ручного управления RabbitMQ
- [ФАКТ] `services/e2e-tests/.../RabbitMQAsyncE2eTest.java` (TC-22, TC-23) и
  `NotificationServiceE2eTest.java` (TC-25) содержат инструкции `docker stop/start smp-rabbitmq`
  и оговаривают, что при доступном RabbitMQ ветка rollback не воспроизводится.
- [ГИПОТЕЗА] в `practice-run.yml` job `e2e-tests` просто запускает `mvn ... test`; можно
  предположить, что TC-23/25 могут быть flaky/непоказательны в CI.
- Вопрос: считать ли их smoke-проверкой или требовать ручного прогона.

### Q8. `skipE2eTests` не действует?
- [ФАКТ] `services/pom.xml:34` задаёт `<skipE2eTests>true</skipE2eTests>`, профиль
  `e2e-tests` ставит `false`. Но в `services/e2e-tests/pom.xml:98-101` конфигурация
  surefire с `skipTests` закомментирована.
- Вопрос: гоняются ли e2e-тесты при обычном `make mvn-test`? Требует локальной проверки.

### Q9. Назначение `starters/` в текущей модели курса
- [ФАКТ] `README.md:134` описывает `starters/` (Java/Go/Python/TypeScript).
  `docs/submission-guide.md` и чек-листы про starter kits не упоминают, т.к. курс
  переведён на модель «тестируем готовую систему».
- Вопрос: `starters/` — легаси прежней модели или опорный материал для отдельных заданий?

### Q10. Локальные заготовки card-management
- [ФАКТ] `services/card-management/docker-compose.local.yml`, `example.env`,
  `.gitignore` и `services/merchant-acquirer/.env.example` не упомянуты ни в README,
  ни в `docs/`.
- Вопрос: поддерживаемый сценарий локальной разработки или артефакт прошлых итераций?

### Q11. Возможная ошибка default-конфига terminal-simulator
- [ФАКТ] `services/terminal-simulator/src/main/resources/application.yml` задаёт
  `card-management-url: ${CARD_MGMT_URL:http://localhost:8080}` — по умолчанию порт
  Gateway, а не Card Management `8081`. В compose передаётся корректный
  `CARD_MGMT_URL=http://card-management:8080`.
- Вопрос: это осознанный fallback или дефект конфигурации вне compose.

### Q12. Расположение студенческих репозиториев
- [ФАКТ] `docs/submission-guide.md:2-5` описывает модель `Practic{Имя}{Фамилия}` в
  организации. Конкретная org в документе указана как `<org>`; remote эталона —
  `LektonSoftwareTestingCourse/Practic` (`git remote -v`).
- Вопрос: где живут студенческие репозитории и кто их создаёт (куратор вручную?).

## Связи

- Противоречия по архитектуре: [02_ARCHITECTURE.md](02_ARCHITECTURE.md),
  [07_DOMAIN_MODEL.md](07_DOMAIN_MODEL.md).
- Пайплайн сдачи и CI: [03_DATA_FLOW.md](03_DATA_FLOW.md),
  [04_ENTRYPOINTS.md](04_ENTRYPOINTS.md).
