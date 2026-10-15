# А1 — C4 Context

## Чего не хватало и что добавлено

В [исходной схеме][oldarch] сервисы раскрыты сразу: отдельной карты границы СМП нет. Здесь СМП показана одним блоком, внешние роли отделены от ядра, подписаны направления и протоколы. «Внешний» означает роль относительно процессинга, а не размещение вне Docker Compose.

```mermaid
%%{init: {"theme":"base","themeVariables":{"fontFamily":"Arial, sans-serif","edgeLabelBackground":"#ffffff00"},"flowchart":{"nodeSpacing":75,"rankSpacing":100, "curve": "linear"}}}%%
flowchart LR
    T("<b>Terminal Simulator</b><br/>[Software System]<br/>Эмулирует POS-транзакции")
    M("<b>Merchant Acquirer</b><br/>[Software System]<br/>Эмулирует покупки и рассчитывает<br/>комиссию эквайринга")
    U(["<b>Пользователь Dashboard</b><br/>[Person]<br/>Просматривает транзакции<br/>и статистику процессинга"])
    S("<b>СМП</b><br/>[Software System]<br/>Авторизация, карты, журнал,<br/>уведомления и Dashboard")
    B("<b>BIN Lookup</b><br/>[Software System]<br/>Определяет эмитента по BIN карты")
    T -->|"SYNC · HTTP/JSON"| S
    M -->|"SYNC · HTTP/JSON"| S
    S -->|"Запрашивает комиссию<br/>SYNC · HTTP/JSON"| M
    U -->|"Загружает SPA и вызывает REST API<br/>SYNC · HTTP/JSON"| S
    S -.->|"Отправляет обновления транзакций<br/>ASYNC · WebSocket/JSON"| U
    S -->|"Запрашивает эмитента<br/>SYNC · HTTP/JSON"| B
    classDef actor fill:#f0fdf4,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef external fill:#fef2f2,stroke:#b91c1c,stroke-width:2px,color:#991b1b
    classDef internal fill:#eff6ff,stroke:#2563eb,stroke-width:3px,color:#1e40af
    class U actor
    class T,M,B external
    class S internal
    %% ceasg:{"id":"86a0eqif"} %%
    linkStyle default stroke:#64748b,stroke-width:1.5px;
    %% mermaid-flow:pos T=177,240 M=1172,100 U=1173,374 S=610,240 B=1173,240
```

## Подтверждение связей кодом

| Связь | Основание |
|---|---|
| Терминал → СМП | [Terminal GatewayClient][termclient]: `sendToGateway()` отправляет POST в Gateway, `getCardsFromCardManager()` читает CMS напрямую. |
| Мерчант → СМП | [Merchant GatewayClient][merchclient]: `processAuthorize()` и `getCards()` используют Gateway. |
| СМП → мерчант | [MerchantAcquirerClient.fetchAcquiringFee()][feeclient]: отдельный HTTP-запрос комиссии из Switch; это не AMQP. |
| Пользователь ↔ Dashboard | [nginx.conf][nginx]: SPA, `/api/` → Gateway, `/ws/` → Logger; [useWebSocket()][ws] открывает `/ws/transactions`. |
| СМП → BIN Lookup | [BinLookupClient.getIssuerId()][binclient]: HTTP, таймауты 3/5 с, при ошибке `Optional.empty()`. |
| Физическое размещение | [docker-compose.yaml][compose]: эмуляторы и BIN Lookup поднимаются локально вместе с СМП. |


[oldarch]: ../../../docs/architecture.md
[termclient]: ../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java
[merchclient]: ../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java
[feeclient]: ../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java
[nginx]: ../../../services/dashboard/nginx.conf
[ws]: ../../../services/dashboard/src/hooks/useWebSocket.ts
[binclient]: ../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java
[compose]: ../../../docker-compose.yaml
