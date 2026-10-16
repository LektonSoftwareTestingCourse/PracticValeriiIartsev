# А4 — Sequence транзакции

## Чего не хватало и что добавлено

[Исходная sequence][oldarch] объединяет happy path и короткую ветку declined, но не раскрывает BLOCKED и компенсацию при сбое публикации. В ней также переставлены CMS/BIN и лимиты/баланс. Ниже показаны три фактических сценария и отдельно — требуемый, но не реализованный сценарий confirm-timeout с reversal `0400`.

## Happy path

```mermaid
%%{init: {"look":"classic","layout":"dagre","fontFamily":"Arial, sans-serif","themeVariables":{"fontFamily":"Arial, sans-serif"},"sequence":{"width":200,"actorMargin":60,"wrap":true,"wrapPadding":10}}}%%
sequenceDiagram
    actor C as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant CMS as Card Management
    participant B as BIN Lookup
    participant DB as PostgreSQL
    participant M as Merchant Acquirer
    participant R as RabbitMQ
    participant L as Logger
    C->>G: HTTP POST /api/transactions, mti=0100
    G->>G: TransactionValidationFilter
    G->>S: HTTP POST /api/internal/route
    S->>S: normalize, BIN → issuerId
    S->>A: HTTP POST /api/internal/authorize
    A->>CMS: HTTP GET /api/cards/{pan}
    CMS->>DB: JDBC SELECT cards
    CMS-->>A: Карта ACTIVE, срок, баланс, лимиты
    A->>B: HTTP GET /api/bin/{bin}
    B-->>A: issuerId или fallback Optional.empty
    A->>A: Статус → срок → баланс
    A->>DB: JDBC upsertLimitUsage + RRN
    DB-->>A: Лимиты соблюдены, usage увеличен
    A->>CMS: HTTP POST /api/cards/{pan}/reserve, amount + rrn
    CMS->>DB: JDBC transaction: lock card, reservation, balance, outbox
    DB-->>CMS: COMMIT
    CMS-->>A: HTTP 200, карта после резерва
    A-->>S: HTTP 200, APPROVED/00, rrn, authCode
    Note over A,DB: Локальная транзакция Authorization фиксирует usage
    S->>M: HTTP POST /api/simulator/merchant/fee
    M-->>S: Комиссия или null при ошибке
    S-)R: AMQP smp.transactions, key transaction.log
    Note over S,R: Сейчас convertAndSend возвращается без ожидания confirm
    par HTTP-ответ
        S-->>G: HTTP 200, APPROVED/00
        G-->>C: HTTP 200, APPROVED/00
    and Независимая обработка журнала
        R-)L: AMQP queue transaction-log
        L->>DB: JDBC saveAndFlush transactions
        L-)C: WebSocket через Dashboard, если клиент подписан
    end
    Note over CMS,R: Outbox reserve обрабатывается отдельным scheduler, см. А3 и А5
```

Код: [Gateway routes][gatewayroutes], [RouteService.route()][route], [AuthServiceImpl.authorize()][auth], [CardServiceImpl.reserve()][cardservice], [LoggerClient.log()][loggerclient], [TransactionLogListener][loglistener], [TransactionService.store()][logservice].

## Declined при BLOCKED

```mermaid
%%{init: {"look":"classic","layout":"dagre","fontFamily":"Arial, sans-serif","themeVariables":{"fontFamily":"Arial, sans-serif"},"sequence":{"width":200,"actorMargin":60,"wrap":true,"wrapPadding":10}}}%%
sequenceDiagram
    actor C as Клиент
    participant G as Gateway
    participant S as Switch
    participant A as Authorization
    participant CMS as Card Management
    participant B as BIN Lookup
    participant M as Merchant Acquirer
    participant R as RabbitMQ
    participant L as Logger
    C->>G: HTTP POST /api/transactions
    G->>S: HTTP POST /api/internal/route
    S->>A: HTTP POST /api/internal/authorize
    A->>CMS: HTTP GET /api/cards/{pan}
    CMS-->>A: Карта BLOCKED
    A->>B: HTTP GET /api/bin/{bin}
    B-->>A: Данные или Optional.empty
    A->>A: DeclineOutcome.<br/>CARD_BLOCKED
    Note over A,CMS: Нет upsert usage и reserve
    A-->>S: HTTP 403, DECLINED/05, CARD_BLOCKED
    Note over S,A: AuthorizationClient читает бизнес-ответ даже при non-2xx
    S->>M: HTTP POST /api/simulator/merchant/fee
    M-->>S: Комиссия или null
    S-)R: AMQP TransactionRequest, status=DECLINED
    par HTTP-ответ
        S-->>G: HTTP 200, DECLINED/05, CARD_BLOCKED
        G-->>C: HTTP 200, DECLINED/05, CARD_BLOCKED
    and Журнал отказа
        R-)L: AMQP transaction-log
        L->>L: Сохранить в PostgreSQL
    end
```

Код: [DeclineOutcome][declines], [AuthControllerImpl.authorize()][authcontroller], [AuthorizationClient.callAuthorize()][authclient], [RouteController.routeTransaction()][routecontroller], [RouteService][route]. HTTP 403 **внутреннего Authorization** и HTTP 200 **Gateway/Switch** — разные контракты.

## Реальная компенсация при ошибке публикации

```mermaid
%%{init: {"look":"classic","layout":"dagre","fontFamily":"Arial, sans-serif","themeVariables":{"fontFamily":"Arial, sans-serif"},"sequence":{"width":200,"actorMargin":60,"wrap":true,"wrapPadding":10}}}%%
sequenceDiagram
    actor C as Клиент
    participant S as Switch через<br/>Gateway
    participant P as LoggerClient /<br/>RabbitTemplate
    participant A as Authorization
    participant CMS as Card Management
    participant DB as PostgreSQL
    Note over S,DB: Авторизация уже APPROVED, резерв создан, usage увеличен
    S->>P: Java log(TransactionRequest APPROVED)
    P->>P: convertAndSend выбрасывает AmqpException
    P-->>S: false
    S->>A: HTTP POST /api/internal/rollback, rrn + pan + amount
    Note over S,A: В RollbackRequest нет mti, это не передача сообщения 0400
    A->>CMS: HTTP POST /api/cards/{pan}/rollback
    alt Компенсация успешна
        CMS->>DB: JDBC transaction: reservation ROLLED_BACK, balance + amount
        DB-->>CMS: COMMIT, rollback row + outbox
        CMS-->>A: HTTP 200
        A-->>S: HTTP 200, rollback APPROVED
    else Ошибка / недоступность компенсации
        CMS-->>A: Ошибка или timeout
        A-->>S: DECLINED либо null при ошибке клиента Switch
        Note over S,DB: Резерв может остаться RESERVED
    end
    S-->>C: HTTP 200 через Gateway, DECLINED/96
    Note over A,DB: limit_usage не уменьшается в AuthServiceImpl.rollback
    Note over S,P: После rollback нет новой<br/>публикации DECLINED/96<br/>в Logger
```

Код: [LoggerClient][loggerclient] ловит `AmqpException`; [RouteService][route] инициирует rollback только для первоначального APPROVED. [AuthorizationClient.rollback()][authclient] создаёт [RollbackRequest][rollbackrequest]. [AuthServiceImpl.rollback()][auth] и [CardServiceImpl.rollback()][cardservice] реализуют компенсацию. При исходном DECLINED ошибка логирования не инициирует rollback.

## Требуемый сценарий: confirm-timeout и reversal 0400

Сценарий из задания А4–А5 и [ТЗ Authorization: reversal][authtz]. **Эта диаграмма описывает требование, а не текущую реализацию.** Показано продолжение успешной авторизации с уже созданным резервом; HTTP-маршрут приёма reversal `0400` в коде отсутствует.

```mermaid
%%{init: {"look":"classic","layout":"dagre","fontFamily":"Arial, sans-serif","themeVariables":{"fontFamily":"Arial, sans-serif"},"sequence":{"width":200,"actorMargin":60,"wrap":true,"wrapPadding":10}}}%%
sequenceDiagram
    actor C as Клиент
    participant G as Gateway
    participant S as Switch
    participant R as RabbitMQ
    participant A as Authorization
    participant CMS as Card Management
    participant DB as PostgreSQL
    Note over S,DB: Требование: авторизация APPROVED, резерв создан
    S-)R: ASYNC AMQP · публикация транзакции
    Note over S,R: Publisher confirm не получен за 2 с
    S->>A: SYNC HTTP · reversal, mti=0400<br/>связь с исходными rrn, pan, amount
    A->>CMS: SYNC HTTP · отмена резерва
    CMS->>DB: SYNC JDBC · возврат суммы, резерв ROLLED_BACK
    DB-->>CMS: COMMIT
    CMS-->>A: Компенсация успешна
    A->>DB: SYNC JDBC · отмена расхода по исходной операции
    DB-->>A: COMMIT usage
    A-->>S: Результат reversal
    S-->>G: HTTP 200, DECLINED/96
    G-->>C: HTTP 200, DECLINED/96
    Note over S,R: Timeout confirm не доказывает,<br/>что публикация не дошла до брокера
```

### Отличия от кода

| Чего нет | Что есть в коде |
|---|---|
| Ожидания publisher confirm до 2 секунд перед ответом APPROVED | В [настройках Switch][swconfig] включён `publisher-confirm-type: correlated`, но [LoggerClient.log()][loggerclient] возвращает `true` сразу после `convertAndSend()`, без ожидания ack. `logger-read-timeout-ms: 2000` относится к HTTP, а не к AMQP confirm. |
| Компенсации по nack или истечению таймаута confirm | [LoggerClient][loggerclient] возвращает `false` при синхронном `AmqpException`. Только этот результат после первоначального APPROVED запускает rollback в [RouteService.route()][route]. Отдельной обработки nack/confirm-timeout в этом пути нет. |
| Отправки reversal-сообщения с `mti=0400` при компенсации | [AuthorizationClient.rollback()][authclient] отправляет HTTP `POST /api/internal/rollback` с [RollbackRequest][rollbackrequest]: `rrn`, `pan`, `amount`, без MTI. Метод [AuthorizationRequest.forReversal()][authrequest] создаёт DTO с `0400`, но вызовов этого метода в коде нет. |
| Приёма `mti=0400` через транзакционный вход Gateway | [TransactionRequestValidator][validator] принимает только `0100`; другое значение MTI отклоняется валидацией. |

[oldarch]: ../../../docs/architecture.md
[gatewayroutes]: ../../../services/gateway/src/main/resources/application.yml
[route]: ../../../services/switch/src/main/java/com/processing/service/RouteService.java
[auth]: ../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java
[cardservice]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java
[loggerclient]: ../../../services/switch/src/main/java/com/processing/service/LoggerClient.java
[loglistener]: ../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java
[logservice]: ../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java
[declines]: ../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java
[authcontroller]: ../../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java
[authclient]: ../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java
[routecontroller]: ../../../services/switch/src/main/java/com/processing/controller/RouteController.java
[rollbackrequest]: ../../../services/common/src/main/java/com/processing/common/dto/authorization/RollbackRequest.java
[swconfig]: ../../../services/switch/src/main/resources/application.yml
[authrequest]: ../../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java
[validator]: ../../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java
[authtz]: ../../../tz/04-authorization.md
