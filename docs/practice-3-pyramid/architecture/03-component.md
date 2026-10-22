# А3 — C4 Component для Authorization и Card Management

## Чего не хватало и что добавлено

[Исходная архитектура][oldarch] не раскрывает внутренности сервисов. Добавлены контроллеры, бизнес-логика, HTTP-клиенты, persistence-адаптеры и отдельный outbox worker. Сплошные связи внутри сервиса — вызовы Java, а не сетевой HTTP; сетевые границы явно подписаны.

## Authorization

![alt text](../content/A3-component.png)

Основания: [AuthControllerImpl][authcontroller], [AuthServiceImpl][auth], [CardManagementClientImpl][cmsclient], [BinLookupClient][binclient], [LimitUsageRepository][limits].

- `authorize()` начинает локальную транзакцию БД; чтение карты и reserve — отдельные HTTP-вызовы в CMS. Единой ACID-транзакции двух сервисов нет.
- Порядок: `getCard` → BIN Lookup → статус → срок → баланс → `upsertLimitUsage` → RRN → reserve → authCode. Срок сравнивается с UTC-датой `transmissionDateTime`, а не только с системными часами.
- Правило дневного/месячного лимита находится **в SQL репозитория**. Мок, возвращающий `0` или `1`, проверяет реакцию сервиса, но не арифметику/атомарность SQL.
- Результат BIN Lookup используется для записи в лог. Он не подменяет `request.issuerId()` и не определяет решение об одобрении.
- Пойманная ошибка reserve превращается в обычный DECLINED-ответ. Само `rollbackFor` не доказывает отмену ранее обновлённого usage: исключение обработано внутри метода. Отдельный `rollback()` вызывает только CMS и usage не компенсирует.

## Card Management

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","edgeLabelBackground":"#ffffff00"},"flowchart":{"nodeSpacing":45,"rankSpacing":70,"wrappingWidth":340,"htmlLabels":true},"look":"classic","layout":"dagre","fontFamily":"Arial, sans-serif","markdownAutoWrap":false,"themeCSS":".nodeLabel, .edgeLabel, .cluster-label { font-family: Arial, sans-serif !important; white-space: nowrap !important; }"}}%%
flowchart TB
    subgraph CMS ["Card Management<br/>[Container]"]
        CGC["<b>CardGeneratorController</b><br/>[Component]"]
        DOMAIN["<b>Card / Reservation</b><br/>[Component]<br/>Баланс, статусы, переходы"]
        PG["<b>PanGenerator</b><br/>[Component]<br/>Реализация: LuhnValidator"]
        RETRY["<b>RetryService</b><br/>[Component]<br/>Коллизии PAN"]
        TR["<b>TransactionRunnerImpl</b><br/>[Component]<br/>Spring transaction boundary"]
        BS["<b>BinIssuerServiceImpl</b><br/>[Component]"]
        REPOS["<b>CardRepository /<br/>ReservationRepository /<br/>ReservationRollbackRepository</b><br/>[Component]<br/>Persistence adapters + JpaRepository"]
        OEP["<b>OutboxEventProcessorImpl</b><br/>[Component]<br/>save / processSingleEvent"]
        RT["<b>RabbitTemplate</b><br/>[Component]"]
        BR["<b>BinIssuerRepository</b><br/>[Component]<br/>BinIssuerJpaAdapter"]
        CG["<b>CardGeneratorService</b><br/>[Component]"]
        CS["<b>CardServiceImpl</b><br/>[Component]<br/>CRUD / reserve / rollback"]
        CC["<b>CardController</b><br/>[Component]<br/>DTO, CardRestMapper /<br/>CardStatusMapper"]
        EV["<b>CardEventNotifierImpl</b><br/>[Component]"]
        OP["<b>OutboxProcessor</b><br/>[Component]<br/>Scheduled process, fixedDelay"]
        OR["<b>OutboxRepository</b><br/>[Component]<br/>OutboxJpaAdapter + JpaRepository"]
    end
    HTTP("<b>HTTP-клиент</b><br/>[Software System]<br/>Внешний потребитель API")
    DB[("<b>PostgreSQL</b><br/>[Container]<br/>cards / reservations /<br/>reservations_rollbacks<br>bin_issuers<br>outbox_event")]
    MQ[["<b>RabbitMQ</b><br/>[Container]"]]
    HTTP -->|"SYNC · HTTP<br/>/api/cards/*"| CC
    CC -->|"Java"| CS
    CGC -->|"Java"| CG
    CG -->|"Java<br/>createCards"| CS
    CS -->|"Java"| DOMAIN
    CS -->|"Java<br/>generatePan"| PG
    CS -->|"Java<br/>getIssuerId"| BS
    BS -->|"Java"| BR
    CS -->|"Java<br/>повтор создания"| RETRY
    CS -->|"Java<br/>PATCH / DELETE / reserve / rollback"| TR
    CS -->|"Java · CRUD<br/>внутри TR для изменения"| REPOS
    CS -->|"Java<br/>notifyListeners"| EV
    EV -->|"Java · save<br/>CardOutboxEventData PENDING"| OEP
    OP -->|"Java<br/>processSingleEvent"| OEP
    OP -->|"Java<br/>findPending"| OR
    OEP -->|"Java · save<br/>PENDING / PROCESSED / FAILED"| OR
    OEP -->|"Java<br/>convertAndSend"| RT
    REPOS -->|"SYNC · JDBC"| DB
    RT -.->|"ASYNC · AMQP<br/>smp.card-events<br/>card.EventClass"| MQ
    BR -->|"SYNC JDBC"| DB
    OR -->|"SYNC JDBC"| DB
    style CMS fill:none,stroke:#2563eb,stroke-width:2px,color:#2563eb
    classDef component fill:#eff6ff,stroke:#2563eb,stroke-width:2px,color:#1e40af
    classDef internal fill:#eff6ff,stroke:#2563eb,stroke-width:2px,color:#1e40af
    classDef external fill:#fef2f2,stroke:#b91c1c,stroke-width:2px,color:#991b1b
    class CC,CGC,CG,CS,DOMAIN,PG,RETRY,TR,BS,BR,REPOS,EV,OEP,OR,OP,RT component
    class DB,MQ internal
    class HTTP external
    %% ceasg:{"id":"n6demdf6"} %%
    linkStyle default stroke:#64748b,stroke-width:1.5px;
    %% mermaid-flow:pos HTTP=2371,232 CC=1796,237 CGC=194,134 CG=193,254 CS=1109,243 DOMAIN=198,694 PG=474,694 RETRY=701,694 TR=930,694 BS=1771,694 BR=1774,816 REPOS=2060,694 EV=1172,691 OEP=1444,904 OR=1793,1048 OP=1446,697 RT=1444,1108 DB=2381,1044 MQ=1444,1275
    %% mermaid-flow:gpos CMS=61,60,2169,1106
```

Настройка бинов и адаптеров подтверждается [AppConfig][cmsapp]; бизнес-методы — [CardServiceImpl][cardservice], [Card][card], [Reservation][reservation]; контроллер — [CardController][cardcontroller].

Ветка пакетной генерации: [CardGeneratorController][generatorcontroller] → [CardGeneratorService.generate()][generator] → `CardServiceImpl.createCards()`. Генератор распределяет карты по BIN и создаёт набор статусов ACTIVE/INACTIVE/BLOCKED.

События идут по цепочке [CardEventNotifierImpl][notifier] → [OutboxEventProcessorImpl][outbox] → [OutboxJpaAdapter][outboxrepo]. Публикацию инициирует [OutboxProcessor][scheduler], а не HTTP-контроллер. Таблица и поля статуса заданы в [миграции V5.5][outboxmigration].

[oldarch]: ../../../docs/architecture.md
[authcontroller]: ../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java
[auth]: ../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java
[cmsclient]: ../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java
[binclient]: ../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java
[limits]: ../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java
[cmsapp]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/configuration/AppConfig.java
[cardservice]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java
[card]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java
[reservation]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/models/Reservation.java
[cardcontroller]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java
[generatorcontroller]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardGeneratorController.java
[generator]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardGeneratorService.java
[notifier]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/events/CardEventNotifierImpl.java
[outbox]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java
[outboxrepo]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxJpaAdapter.java
[scheduler]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java
[outboxmigration]: ../../../services/card-management/src/main/resources/db/migration/V5.5__init_outbox_events_table.sql
[runner]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java
[cardjpa]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java
[cmsconfig]: ../../../services/card-management/src/main/resources/application.properties
[outboxmodel]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardOutboxEventData.java
