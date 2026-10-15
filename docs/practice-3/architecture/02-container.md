# А2 — C4 Container

## Чего не хватало и что добавлено

В [PlantUML][oldpuml] и [исходной Mermaid-схеме][oldarch] нет системной разметки sync/async, JDBC Merchant и запроса комиссии из Switch. Здесь эти связи добавлены, outbox подписан как часть CMS, WebSocket отделён от AMQP, состав сверён с [Compose][compose].


```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","edgeLabelBackground":"#ffffff00"},"flowchart":{"nodeSpacing":45,"rankSpacing":65}}}%%
flowchart TB
    subgraph SMP ["СМП — Симулятор процессингового центра<br>[Softwarre System]"]
        WEB("<b>Dashboard</b><br>[Container]<br>React SPA + nginx :3000")
        GW("<b>Gateway</b><br>[Container]<br>Spring Cloud Gateway :8080")
        LOG("<b>Transaction Logger</b><br/>[Container]<br/>Spring Boot :8088")
        CMS("<b>Card Management</b><br/>[Container]<br/>Spring Boot :8081<br/>CRUD + outbox worker")
        SW("<b>Switch</b><br/>[Container]<br/>Spring Boot :8082")
        R[["<b>RabbitMQ</b><br/>[Container]<br/>AMQP :5672<br/>Management :15672"]]
        A("<b>Authorization</b><br/>[Container]<br/>Spring Boot :8083")
        DB[("<b>PostgreSQL</b><br/>[Container]<br/>База данных :5432")]
        N("<b>Notification Service</b><br/>[Container]<br/>Spring Boot :8097")
    end
    U(["<b>Пользователь Dashboard</b><br>[Person]"])
    T("<b>Terminal Simulator</b><br>[Container]<br>Spring Boot :8085")
    M("<b>Merchant Acquirer</b><br>[Container]<br>Spring Boot :8084")
    BIN("<b>BIN Lookup</b><br>[Container]<br>Spring Boot :8096")
    U -->|"SYNC · HTTP<br/>SPA и REST/JSON"| WEB
    WEB -->|"SYNC · HTTP<br/>API"| GW
    T -->|"SYNC · HTTP/JSON<br/>транзакции"| GW
    M -->|"SYNC · HTTP/JSON<br/>транзакции и карты"| GW
    GW -->|"SYNC · HTTP<br/>маршрутизация"| SW
    SW -->|"SYNC · HTTP/JSON<br/>авторизация / откат"| A
    A -->|"SYNC · HTTP/JSON<br/>карта / резерв / откат"| CMS
    A -->|"SYNC · HTTP/JSON<br/>эмитент по BIN"| BIN
    T -->|"SYNC · HTTP/JSON<br/>карты"| CMS
    SW -->|"SYNC · HTTP/JSON<br/>комиссия"| M
    GW -->|"SYNC · HTTP<br/>CRUD карт"| CMS
    GW -->|"SYNC · HTTP<br/>поиск и статистика"| LOG
    GW -->|"SYNC · HTTP<br/>симуляция"| T
    GW -->|"SYNC · HTTP<br/>симуляция"| M
    SW -.->|"ASYNC · AMQP<br/>smp.transactions"| R
    CMS -.->|"ASYNC · AMQP<br/>smp.card-events"| R
    R -.->|"ASYNC · AMQP<br/>transaction-log"| LOG
    R -.->|"ASYNC · AMQP<br/>card-notifications"| N
    LOG -.->|"ASYNC · WebSocket/JSON<br/>через nginx Dashboard"| U
    CMS -->|"SYNC · JDBC"| DB
    A -->|"SYNC · JDBC"| DB
    M -->|"SYNC · JDBC"| DB
    LOG -->|"SYNC · JDBC"| DB
    N -->|"SYNC · JDBC"| DB
    style SMP fill:none,stroke:#2563eb,stroke-width:2px,color:#2563eb
    classDef actor fill:#f0fdf4,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef external fill:#fef2f2,stroke:#b91c1c,stroke-width:2px,color:#991b1b
    classDef internal fill:#eff6ff,stroke:#2563eb,stroke-width:2px,color:#1e40af
    class U actor
    class T,M,BIN external
    class WEB,GW,LOG,CMS,SW,R,A,DB,N internal
    linkStyle 0 stroke:#ffffff,stroke-width:2px
    linkStyle 1 stroke:#ffffff,stroke-width:2px
    linkStyle 4 stroke:#ffffff,stroke-width:2px
    linkStyle 5 stroke:#ffffff,stroke-width:2px
    linkStyle 6 stroke:#ffffff,stroke-width:2px
    %% ceasg:{"id":"fb9hrt4b"} %%
    linkStyle default stroke:#64748b,stroke-width:1.5px;
    %% Основной путь: пользователь → Dashboard → Gateway → Switch → Authorization → Card Management.
    %% mermaid-flow:pos WEB=844,253 GW=844,368 LOG=1219,371 CMS=1222,747 SW=845,510 R=1346,516 A=845,741 DB=851,1202 N=1354,1200 U=973,90 T=256,378 M=254,520 BIN=251,743
    %% mermaid-flow:gpos SMP=386,204,1124,1127
```

## Подтверждение кодом

- Перечень, сети, профиль и внутренние адреса: [Compose][compose]. Вспомогательные связи: [autoscaler.sh][autoscaler], [Prometheus scrape config][prometheus], конфигурации `infra/grafana/provisioning/` и `infra/promtail/`.
- HTTP-маршруты входа: [Gateway routes][gatewayroutes]; эмуляторы: [Terminal client][termclient], [Merchant client][merchclient].
- Switch → Authorization/Merchant и публикация: [RouteService.route()][route], [AuthorizationClient][authclient], [MerchantAcquirerClient][feeclient], [LoggerClient][loggerclient].
- Authorization → CMS/BIN/БД: [AuthServiceImpl][auth], [CardManagementClientImpl][cmsclient], [BinLookupClient][binclient], [LimitUsageRepository][limits].
- JDBC CMS и outbox: [CardRepositoryPersistenceAdapter][cardrepo], [OutboxJpaAdapter][outboxrepo], [OutboxProcessor][scheduler]. JDBC Merchant: [application.yaml][merchconfig] и его Spring Data-репозитории.
- Очереди: [Switch RabbitMQConfig][swrabbit], [CMS RabbitMQConfig][cmsrabbit]; сохранение consumer: [TransactionService][logservice], [CardEventListener][notifylistener].
- REST и WebSocket Dashboard: [nginx.conf][nginx], [useWebSocket()][ws].

[oldpuml]: ../../../docs/architecture-diagram.puml
[oldarch]: ../../../docs/architecture.md
[compose]: ../../../docker-compose.yaml
[autoscaler]: ../../../scripts/autoscaler.sh
[prometheus]: ../../../infra/prometheus/prometheus.yml
[gatewayroutes]: ../../../services/gateway/src/main/resources/application.yml
[termclient]: ../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java
[merchclient]: ../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java
[route]: ../../../services/switch/src/main/java/com/processing/service/RouteService.java
[authclient]: ../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java
[feeclient]: ../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java
[loggerclient]: ../../../services/switch/src/main/java/com/processing/service/LoggerClient.java
[auth]: ../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java
[cmsclient]: ../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java
[binclient]: ../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java
[limits]: ../../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java
[cardrepo]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardRepositoryPersistenceAdapter.java
[outboxrepo]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/OutboxJpaAdapter.java
[scheduler]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java
[merchconfig]: ../../../services/merchant-acquirer/src/main/resources/application.yaml
[swrabbit]: ../../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java
[cmsrabbit]: ../../../services/card-management/src/main/java/com/processing/cardmanagement/config/RabbitMQConfig.java
[logservice]: ../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java
[notifylistener]: ../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java
[nginx]: ../../../services/dashboard/nginx.conf
[ws]: ../../../services/dashboard/src/hooks/useWebSocket.ts
