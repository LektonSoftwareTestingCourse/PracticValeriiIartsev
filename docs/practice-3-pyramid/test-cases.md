# Тест-кейсы и анализ пирамиды

## Выбор уровня

| Уровень | Что проверяется | Окружение |
|---|---|---|
| unit | Логика публичного метода, вычисления и команды зависимостям | Настоящий проверяемый класс; зависимости заменены моками, без сети и БД |
| api | HTTP-маршрут, валидация, сериализация и коды ответа | Настоящий web-слой; зависимости, не участвующие в проверке, заменены стабами |
| integration | Сохранение, SQL, транзакции, блокировки, HTTP/AMQP-взаимодействие компонентов | Проверяемые сервисы, PostgreSQL и/или RabbitMQ |
| e2e | Результат полного пользовательского сценария | Gateway → Switch → Authorization → CMS, Logger, БД и очередь; Dashboard для N-19 |

## Общие условия

- Каждый кейс независим: отдельные синтетические PAN, STAN и RRN/transactionId; исходное состояние восстанавливается перед прогоном.
- База AUTH: ACTIVE, срок `1026`, баланс 1000; daily usage 200, monthly usage 500; лимиты 1200/1500; сумма 100 копеек; `transmissionDateTime=2026-09-24T10:00:00Z`. BIN `400000` → `ISS001`.
- Остальные поля: `mti=0100`, STAN — 6 цифр, PAN — 16 цифр, `processingCode=000000`, `currencyCode=643`, terminalId — 8 символов, merchantId — 15 символов, `terminalType=POS`, `mcc=5411`, `acquirerId=ACQ001`. Для прямого запроса в Authorization задаётся `issuerId=ISS001`; на внешнем входе его определяет Switch.
- База CMS: зарегистрирован BIN `400000`, имя `IVAN IVANOV`, валюта `643`; в новых кейсах лимиты и баланс задаются явно.
- Для A/P работают реальные Authorization, CMS и PostgreSQL; BIN Lookup может быть HTTP-стабом. Для C/CP с уровнем integration работают CMS и PostgreSQL. Проверки БД выполняются после завершения запроса, из новой транзакции.
- В календарных C-10/C-15/CP время фиксируется внутри изолированного процесса CMS. Поле даты запроса не управляет `YearMonth.now()` в CMS.
- В E2E работают настоящий BIN Lookup и Merchant Acquirer; генераторы нагрузки выключены, Terminal Simulator заменён тестовым HTTP-клиентом. Проверки комиссии в N-19/N-20 нет.
- Rate limiter и circuit breaker изолированы от посторонней нагрузки. В асинхронных проверках используется опрос каждые 100 мс с бюджетом 10 с после разрешения обработки; для TTL в N-22 задан отдельный интервал. Это бюджеты тестов, не SLA.
- Отказы и паузы снимаются после каждого теста, включая неуспешный. При превышении бюджета consumer останавливается; очистка выполняется только в выделенном тестовом окружении.

## Часть Б — новые тест-кейсы

22 кейса на четырёх уровнях. Наборы параметров внутри одной строки считаются одним кейсом.

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| N-01 | [SWITCH: маршрутизация по BIN][switchtz]; [RoutingService][routing] | unit | позитивный | `binRouting={400000:ISS001,400001:ISS002}`; настоящий RoutingService | 1. Вызвать `getIssuerIdByPan()` для синтетических 16-значных PAN с каждым BIN.<br/>2. Повторить с другим суффиксом того же BIN. | `ISS001`/`ISS002` соответствуют первым шести цифрам; суффикс PAN не меняет маршрут. | Классы эквивалентности: известные BIN; контракт функции маршрутизации. |
| N-02 | [SWITCH: неизвестный BIN][switchtz]; [RouteService][route] | unit | негативный | Настоящие RouteService/RoutingService; моки AuthorizationClient, AcquiringFeeClient, LoggerClient; logger возвращает true; BIN `999999` отсутствует | 1. Вызвать `route()` с валидным по формату запросом неизвестного BIN.<br/>2. Захватить сообщение LoggerClient и проверить обращения к зависимостям. | DECLINED/14; AuthorizationClient и AcquiringFeeClient не вызваны; LoggerClient вызван один раз с DECLINED, reserve/rollback отсутствуют. | Класс эквивалентности: неизвестный BIN; ветка исключения UnknownBinException. |
| N-03 | [AUTH: дневной и месячный лимиты][authtz]; [INSERT usage][limits] | integration | граничный | Реальный PostgreSQL и Spring repository; история PAN отсутствует; дата 24.09.2026, сумма 100; каждый набор независим | 1. Вызвать `upsertLimitUsage` с лимитами день/месяц 100/100, 99/100 и 100/99.<br/>2. После каждого вызова завершить транзакцию и прочитать БД из новой. | При 100/100 результат 1, создана одна строка с daily=100 и monthly=100. При 99/100 и 100/99 результат 0, строка не создана. | Граница первой операции; состояние БД без истории расходов. |
| N-04 | [CMS: резервирование][cmstz]; [reserve()][cardservice], [блокировка карты][cardjpa] | integration | негативный | Реальные CMS и PostgreSQL; ACTIVE-карта с балансом 100; два новых разных RRN; отдельные соединения | 1. Одновременно отправить два POST `/api/cards/{pan}/reserve` по 80, синхронизировав старт запросов барьером.<br/>2. Дождаться обоих ответов и прочитать cards/reservations после завершения транзакций. | Один ответ 200, другой 402; порядок несущественен. Баланс 20; один резерв на 80, второго резерва нет. | Сценарий конкурирующих списаний; блокировка и состояние БД. |
| N-05 | [SWITCH: компенсация при сбое логирования][switchtz]; [RouteService][route] | unit | негативный | AuthorizationClient стаб: APPROVED/00 с RRN; LoggerClient возвращает false; rollback стаб возвращает APPROVED; комиссия доступна | 1. Вызвать `route()`.<br/>2. Проверить порядок authorize → log → rollback, аргументы rollback и ответ. | Rollback ровно один раз с исходным PAN, amount и RRN авторизации; наружу DECLINED/96, не APPROVED. | Сценарий отказа async-публикации после одобрения. |
| N-06 | [Outbox retry policy][outbox]; [лимит 3][cmsconfig] | unit | негативный | PENDING-событие, retryCount=2; maxRetryCount=3; RabbitTemplate выбрасывает AmqpException; OutboxRepository — мок | 1. Вызвать публичный `processSingleEvent()`.<br/>2. Захватить объект, переданный в save. | Одна попытка отправки; в save переданы FAILED, retryCount=3, lastError; processedAt не заполнен. | Граничное значение: третья неуспешная попытка публикации. |
| N-07 | [Gateway: валидация][gatewaytz]; [validator][validator] и [filter][validation] | api | негативный | Настоящий HTTP Gateway и validation filter; Switch — записывающий HTTP-stub; валидный запрос, кроме amount=0 | 1. POST `/api/transactions`.<br/>2. Проверить ответ и журнал обращений стаба Switch. | HTTP 400, JSON с error `VALIDATION_ERROR`; Switch не получил запрос. | Граница суммы: 0 вне положительного диапазона внешнего API. |
| N-08 | [Gateway: маршрутизация][gatewaytz]; [routes][gatewayroutes] | api | позитивный | Gateway настоящий; Switch HTTP-stub отвечает известным JSON APPROVED/00; сумма 100 | 1. POST `/api/transactions`.<br/>2. Проверить путь/тело у Switch и тело клиентского ответа. | Один POST `/api/internal/route`; значимые поля запроса не потеряны; ответ HTTP 200 и поля rrn/authCode/status/responseCode стаба переданы без искажений. | Контракт HTTP и правило RewritePath. |
| N-09 | [AuthControllerImpl][authcontroller]; [CARD_BLOCKED][declines] | api | негативный | Настоящий MVC-контроллер Authorization; AuthServiceImpl стаб возвращает DECLINED/05, CARD_BLOCKED; запрос валиден, issuerId заполнен | 1. POST `/api/internal/authorize` напрямую.<br/>2. Проверить HTTP и JSON. | HTTP 403; status DECLINED, responseCode 05, declineReason CARD_BLOCKED. | Контракт: отображение бизнес-отказа в HTTP-код. |
| N-10 | [CMS: PATCH][cmstz]; [PatchCardRequest][patchrequest], [CardController][cardcontroller] | api | негативный | Настоящий MVC-слой CMS и exception advice; CardService — мок; PAN валиден | 1. PATCH `/api/cards/{pan}` с `{"dailyLimit":-1}`.<br/>2. Проверить ответ и отсутствие вызова CardService.patchCard. | HTTP 400 до выполнения бизнес-метода. | Граничное значение: отрицательный лимит; контракт DTO. |
| N-11 | [AUTH: накопление usage][authtz]; [SQL смены периода][limits] | integration | граничный | Реальный PostgreSQL и Spring repository; строка PAN за 24.09.2026: daily=200, monthly=500; лимиты 100/600; сумма 100 | 1. Вызвать `upsertLimitUsage` за 25.09.2026 и зафиксировать транзакцию.<br/>2. Вызвать его за 01.10.2026 и зафиксировать транзакцию.<br/>3. Прочитать все три строки из новой транзакции. | Оба вызова возвращают 1. За 25.09 создана строка daily=100/monthly=600; за 01.10 — daily=100/monthly=100. Строка за 24.09 не изменена. | Календарные границы дня и месяца; состояние БД и перенос месячного расхода. |
| N-12 | [CMS: резервирование][cmstz]; [reserve()][cardservice] | integration | негативный | Реальные CMS и PostgreSQL; ACTIVE-карта с балансом 1000; новый валидный RRN | 1. POST `/api/cards/{pan}/reserve` на 100.<br/>2. Повторить с тем же PAN/RRN/amount.<br/>3. Прочитать cards и reservations напрямую. | Первый ответ 200, второй 409; баланс 900; ровно один резерв на 100 для этой пары. Повтор не списывает ещё 100. | Состояние БД и сценарий повторного запроса; уникальность RRN/PAN. |
| N-13 | [Logger: асинхронное сохранение и идемпотентность][logtz]; [listener][loglistener], [store()][logservice] | integration | позитивный | Реальные RabbitMQ, Logger, PostgreSQL; WS-клиент подключён до публикации; изолированная очередь; настройки конвертера и доверия десериализации соответствуют Switch/Logger в Compose | 1. Через RabbitTemplate с продуктивной конфигурацией Switch отправить валидный TransactionRequest в `smp.transactions` / `transaction.log`.<br/>2. Дождаться строки.<br/>3. Отправить идентичное сообщение с тем же ID; дождаться окончания второй обработки. | Одна строка с исходными id/rrn/amount/status и одна WS-рассылка; второй проход не создаёт дубль. | Контракт сообщения + состояние БД; повтор доставки одного сообщения. |
| N-14 | Задание А4–А5: confirm ≤2 с и reversal; [LoggerClient][loggerclient], [rollback client][authclient] | integration | негативный | Switch, Authorization, CMS, PostgreSQL и RabbitMQ реальны; AMQP fault-proxy завершает handshake и удерживает publisher basic.ack/basic.nack, не блокируя сам publish; карта из базы AUTH | 1. Отправить запрос непосредственно в Switch.<br/>2. Удерживать confirm более 2 с; отсчитывать время от публикации, не от начала HTTP.<br/>3. Проверить reversal с `mti=0400`, ответ и БД.<br/>4. Снять fault. | **По требованию:** нет APPROVED до ack; примерно через 2 с ожидания (допуск измерения 500 мс) инициирована компенсация; клиент получает DECLINED/96, после успешного rollback баланс восстановлен. Ожидание confirm и reversal `0400` в текущем коде отсутствуют; см. А4. | Сценарий потери подтверждения после отправки; временная граница 2 с. |
| N-15 | [SWITCH: rollback][route]; [AUTH: reversal][authtz]; [CMS rollback][cardservice], [AUTH rollback][auth] | integration | негативный | Реальные Switch/Authorization/CMS/PostgreSQL; fault на AMQP-соединении делает convertAndSend неуспешным с AmqpException; CMS доступен; база AUTH | 1. Авторизовать через Switch.<br/>2. Проверить внешний ответ и cards/reservations/reservations_rollbacks/limit_usage.<br/>3. Повторить `/api/cards/{pan}/rollback` на ту же сумму и RRN. | HTTP 200, DECLINED/96; баланс вернулся с 900 к 1000; резерв ROLLED_BACK, одна строка rollback. Повтор CMS rollback даёт 409 и баланс не растёт. Оба usage должны вернуться к 200/500; отсутствие их компенсации в текущем коде — известное расхождение. | Сценарий компенсации + состояние БД; защита от двойного возврата. |
| N-16 | [CMS: транзакция PATCH + outbox][cardservice]; [TransactionRunner][runner] | integration | негативный | Настоящие Spring CMS и PostgreSQL; ACTIVE-карта, outbox-процессор остановлен; fault-adapter делегирует чтение, но выбрасывает RuntimeException на сохранении outbox после UPDATE карты | 1. PATCH карты на BLOCKED через HTTP.<br/>2. После ответа читать БД из новой транзакции. | HTTP 500 при инъекции RuntimeException; карта осталась ACTIVE, остальные поля неизменны, событие PATCH отсутствует. | Состояние БД: all-or-nothing локальной транзакции. |
| N-17 | [CMS outbox][outbox], [Notification consumer][notifylistener]; eventual consistency | integration | позитивный с восстановлением | CMS, RabbitMQ, Notification и PostgreSQL реальны; автоматический запуск планировщика отключён; process вызывается вручную через Spring bean; consumer Notification сначала остановлен; отдельная ACTIVE-карта | 1. PATCH ACTIVE → BLOCKED; проверить PENDING и payload.pan.<br/>2. На первой process-попытке инъецировать ошибку отправки: проверить PENDING/retryCount=1.<br/>3. Восстановить публикацию и вызвать process снова.<br/>4. Запустить Notification consumer и дождаться уведомления. | После повторной отправки outbox PROCESSED; после запуска consumer есть уведомление с eventType `CardServicePatchEvent`, routingKey `card.CardServicePatchEvent`, исходным pan в JSON. Изменение карты не откатывается из-за задержки Notification. До запуска consumer уведомления нет; deadline отсчитывается после его запуска. | Сценарий восстановления после сбоя + состояние outbox/очереди/БД; async-доставка. |
| N-18 | Задание А5: три попытки consumer, затем DLQ; [Logger topology][swrabbit], [listener][loglistener] | integration | негативный | Выделенные RabbitMQ 3.13, Logger и PostgreSQL; полная топология Switch; валидный TransactionRequest с новым ID; сохранение в БД принудительно завершается ошибкой; число входов в listener измеряется | 1. Опубликовать сообщение в `smp.transactions` с key `transaction.log`.<br/>2. За 10 с дождаться DLQ и посчитать вызовы listener.<br/>3. Прочитать сообщение из `transaction-log-dlq` и проверить БД. | По требованию: 3 неуспешные обработки, затем сообщение с исходным payload в DLQ; строки в transactions нет. Известное расхождение настройки retry приведено ниже. | Сценарий отказа Logger; доставка в DLQ. |
| N-19 | [Стратегия практики 2](../practice-2/test-strategy.md): покупка → журнал → Dashboard; [UI][uitz] | e2e | позитивный | Реальный процессинг, RabbitMQ, Logger, PostgreSQL, Dashboard и локальный BIN Lookup; карта из базы AUTH; симулятор заменён HTTP-клиентом; браузер открыт, WS подключён, открыта первая страница таблицы, фильтры сброшены | 1. POST `/api/transactions` на 100 через Gateway.<br/>2. Проверить ответ и фактический баланс/usage.<br/>3. По RRN дождаться строки через `/api/transactions/search` и появления операции в Dashboard без reload. | APPROVED/00; RRN 12 цифр, authCode 6 A–Z/0–9; баланс 900, usage 300/600; журнал хранит amount=100; Dashboard показывает 1,00 рубля и APPROVED для этой операции. | Сквозной бизнес-сценарий и eventual consistency из чек-листов практики 2. |
| N-20 | [AUTH: статус][authtz], [CMS: PATCH][cmstz]; [чек-листы практики 2](../practice-2/checklists.md) | e2e | негативный | Тот же реальный backend-контур, отдельная ACTIVE-карта; исходные баланс/usage сохранены; новый STAN | 1. PATCH карту на BLOCKED через Gateway; подтвердить состояние напрямую в CMS, не старым кэшированным GET Gateway.<br/>2. POST покупки через Gateway.<br/>3. Проверить баланс/usage и дождаться записи Logger по уникальному STAN + PAN + времени. | Клиент получает HTTP 200, DECLINED/05, CARD_BLOCKED; баланс и usage неизменны; резерв отсутствует; журнал содержит отказ этой операции. | Сквозной переход состояния ACTIVE → BLOCKED → отказ покупки. |
| N-21 | Задание А5: три попытки consumer, затем DLQ; [Notification topology][notifyrabbit], [consumer][notifylistener] | integration | негативный | Выделенные RabbitMQ 3.13, Notification и PostgreSQL; полная топология card-events; валидное JSON-событие PATCH с отдельным PAN; сохранение в БД принудительно завершается ошибкой; число входов в listener измеряется | 1. Опубликовать событие в `smp.card-events` с key `card.CardServicePatchEvent`.<br/>2. За 10 с дождаться DLQ и посчитать вызовы listener.<br/>3. Прочитать сообщение из `card-notifications-dlq` и проверить БД. | По требованию: 3 неуспешные обработки, затем исходное событие в DLQ; уведомления в card_notifications нет. Известное расхождение настройки retry приведено ниже. | Сценарий отказа Notification; доставка в DLQ. |
| N-22 | Задание А5: TTL 60 с; [transaction DLQ][swrabbit], [card DLQ][notifyrabbit], [RabbitMQ TTL][ttl] | integration | граничный | Выделенный RabbitMQ; DLQ пусты, потребителей DLQ нет; два независимых параметра: transaction-log-dlq и card-notifications-dlq | 1. Проверить `x-message-ttl=60000`.<br/>2. Отправить одно диагностическое сообщение через соответствующий DLX и дождаться confirm.<br/>3. Проверить наличие через `basic.get(autoAck=false)` с nack/requeue до истечения TTL; дальнейшую доставку не запрашивать.<br/>4. Через 65 с от подтверждения публикации выполнить `basic.get`. | До истечения TTL сообщение доступно; после 65 с `basic.get` не возвращает сообщение. В основной очереди диагностическое сообщение не появляется. | Временная граница хранения в DLQ; состояние очереди. |

### Покрытие путей и обоснование уровней

| Путь | Кейсы | Почему выбран этот уровень |
|---|---|---|
| BIN routing и команда компенсации | N-01, N-02, N-05 — unit | Проверяются решение и аргументы вызовов; сеть не нужна |
| Ограничение попыток outbox | N-06 — unit | Проверяется переход PENDING → FAILED и счётчик попыток |
| HTTP-вход и контракты | N-07–10 — api | Нужны настоящий маршрут, DTO-валидация и HTTP-ответ |
| SQL-лимиты и календарные периоды | N-03, N-11 — integration | Решение и накопление расходов выполняются в SQL |
| Резерв, конкуренция и компенсация | N-04, N-12, N-15 — integration | Нужны реальные блокировки и проверка сохранённых сумм |
| Изменение карты и outbox | N-16, N-17 — integration | Проверяются общая транзакция и доставка в Notification |
| Logger, confirm, retry и DLQ | N-13, N-14, N-18, N-21, N-22 — integration | Проверяются протокол, доставка, БД и время хранения |
| Покупка и отказ после блокировки | N-19, N-20 — e2e | Проверяется весь маршрут через Gateway, в N-19 также Dashboard |

N-05 проверяет команду компенсации, N-15 — её результат в БД. N-06 проверяет локальное правило повторов, N-17 — восстановление доставки через реальные компоненты.

## Часть В — распределение кейсов практики 2

Источник — [test-design.md](../practice-2/test-design.md): 76 кейсов (21 A, 14 C, 22 P, 19 CP). Сохраняются их ID, данные, шаги и ожидаемые результаты. C-07 и C-09 исключены в исходной работе и в подсчёт не входят.

Для всех A/P проверяются ответ авторизации, резерв, баланс и usage. При успехе дополнительно проверяются RRN из 12 цифр и authCode из 6 символов A–Z/0–9. Эти кейсы имеют уровень integration: прямого вызова сервиса с моками недостаточно для проверки денег и расходов в БД.

Для CP-01–03 проверяются HTTP 201, одна новая ACTIVE-карта, PAN и совпадение сохранённых полей с последующим GET. Для CP-04–19 — HTTP 400 и неизменное число карт. Поэтому CP также относятся к integration. Только C-04 и C-08 ограничены результатом HTTP-операции, без проверки хранения.

### Authorization — классы и границы

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| A-01 | Активная карта | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-02 | Неактивная карта | DECLINED, declineReason=CARD_INACTIVE; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-03 | Заблокированная карта | DECLINED, declineReason=CARD_BLOCKED; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-04 | Статус EXPIRED | DECLINED, responseCode=54; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-05 | Отсутствующий PAN | DECLINED, responseCode=14; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-06 | Прошедший срок | DECLINED, responseCode=54; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-07 | Текущий месяц срока | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-08 | Будущий срок | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-09 | Дневной остаток 99 | DECLINED, responseCode=61; резерв 0, баланс и usage неизменны. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-10 | Дневной остаток 100 | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-11 | Дневной остаток 101 | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-12 | Месячный остаток 99 | DECLINED, responseCode=61; резерв 0, баланс и usage неизменны. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-13 | Месячный остаток 100 | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-14 | Месячный остаток 101 | APPROVED, responseCode=00; резерв 100, баланс 900; оба usage увеличены на 100. | integration | Граница проверяется реальным upsertLimitUsage; после HTTP-запроса читаются резерв и usage. |
| A-15 | Баланс 99 | DECLINED, responseCode=51; резерв 0, баланс и usage неизменны. | integration | Помимо причины отказа проверяется отсутствие резерва и изменений баланса/usage в БД. |
| A-16 | Баланс 100 | APPROVED, responseCode=00; резерв 100, баланс 0; оба usage увеличены на 100. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-17 | Баланс 101 | APPROVED, responseCode=00; резерв 100, баланс 1; оба usage увеличены на 100. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-18 | CMS недоступен при получении карты | DECLINED, responseCode=05, declineReason=ISSUER_TIMEOUT; резерв 0, баланс и usage неизменны. Расхождение с кодом — в таблице ниже. | integration | Отказ HTTP-зависимости CMS и отсутствие изменений проверяются на реальных данных БД. |
| A-19 | Сумма 0 | Запрос отклонён до резервирования; резерв 0, баланс и usage неизменны. Расхождение с кодом — в таблице ниже. | integration | Проверяются отказ прямого HTTP-входа и отсутствие резерва/изменений в БД. |
| A-20 | Сумма 1 | APPROVED, responseCode=00; резерв 1, баланс 999; оба usage увеличены на 1. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |
| A-21 | Сумма 2 | APPROVED, responseCode=00; резерв 2, баланс 998; оба usage увеличены на 2. | integration | Одобрение должно сопровождаться сохранённым резервом и изменением баланса/usage. |

### Card Management — классы и границы

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| C-01 | Создание карты | Карта сохранена: ACTIVE, BIN 400000, PAN из 16 цифр с верной цифрой Луна; баланс и лимиты соответствуют запросу. | integration | Создание через POST, сохранённые поля и последующее чтение требуют CMS и PostgreSQL. |
| C-02 | Неверная длина BIN | Ошибка валидации; новая карта не создана. | integration | Проверяется ошибка HTTP-входа и отсутствие новой строки в БД. |
| C-03 | Недесятичный BIN | Ошибка валидации; новая карта не создана. | integration | Проверяется ошибка HTTP-входа и отсутствие новой строки в БД. |
| C-04 | Получение отсутствующей карты | HTTP 404. | api | Проверяется отображение CardNotFoundException в HTTP 404; сервис можно заменить стабом. |
| C-05 | Изменение статуса | Статус BLOCKED сохранён; остальные поля неизменны. | integration | PATCH и последующий GET проверяют сохранение статуса, а не передачу объекта в мок. |
| C-06 | Мягкое удаление | GET возвращает 404; карты нет в списке; запись в БД сохранена со статусом DELETED. | integration | Нужны сохранение DELETED, SQLRestriction и реальная фильтрация выборки. |
| C-08 | Формат и контрольная цифра PAN | PAN содержит ровно 16 цифр, начинается с 400000 и проходит проверку Луна. | api | Проверяется PAN в результате создания; HTTP-слой, сервис и генератор настоящие, BIN и репозиторий — стабы. |
| C-10 | Срок через три года | expiryDate=1229, формат MMYY. | integration | POST и GET проверяют рассчитанный, сериализованный и сохранённый срок. |
| C-11 | Пакетная генерация | Создано 20 карт, по 10 на BIN; PAN уникальны, имеют 16 цифр и проходят Лун. | integration | Нужны реальные 20 созданных карт, распределение по BIN и уникальность сохранённых PAN. |
| C-12 | Фильтрация и пагинация | HTTP 200; 10 различных ACTIVE-карт, total=25; BLOCKED отсутствуют. | integration | Состав страницы и total определяются реальными SQL-запросами выборки и подсчёта. |
| C-13 | Резерв равен балансу | HTTP 200; баланс 0, уменьшение ровно на 100. | integration | После HTTP-резерва проверяется сохранённый баланс 0. |
| C-14 | Резерв превышает баланс | HTTP 402; баланс 100, резерв не создан. | integration | HTTP 402 проверяется вместе с отсутствием резерва и неизменным балансом в БД. |
| C-15 | Смена года при генерации срока | Сроки соответственно 1229 и 0130; месяц в диапазоне 01…12. | integration | Обе созданные карты читаются после сохранения; контролируется время внутри CMS. |
| C-16 | Резерв меньше баланса | HTTP 200; баланс 1, уменьшение ровно на 99. | integration | После HTTP-резерва проверяется сохранённый баланс 1. |

### Authorization — попарный набор

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| P-01 | закреплённый кейс — статус | DECLINED, declineReason=CARD_BLOCKED; резерв 0; баланс 99; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-02 | закреплённый кейс — срок | DECLINED, responseCode=54; резерв 0; баланс 99; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-03 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 1; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |
| P-04 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 0; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |
| P-05 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 1; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |
| P-06 | баланс | DECLINED, responseCode=51; резерв 0; баланс 99; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-07 | срок | DECLINED, responseCode=54; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-08 | срок | DECLINED, responseCode=54; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-09 | дневной лимит | DECLINED, responseCode=61; резерв 0; баланс 100; usage дня 200, месяца 500. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-10 | дневной лимит | DECLINED, responseCode=61; резерв 0; баланс 100; usage дня 200, месяца 500. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-11 | срок | DECLINED, responseCode=54; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-12 | баланс | DECLINED, responseCode=51; резерв 0; баланс 99; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-13 | статус | DECLINED, responseCode=54; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-14 | дневной лимит | DECLINED, responseCode=61; резерв 0; баланс 99; usage дня 200, месяца 500. Расхождение с кодом — в таблице ниже. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-15 | месячный лимит | DECLINED, responseCode=61; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-16 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 1; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |
| P-17 | дневной лимит | DECLINED, responseCode=61; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-18 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 0; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |
| P-19 | статус | DECLINED, declineReason=CARD_BLOCKED; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-20 | месячный лимит | DECLINED, responseCode=61; резерв 0; баланс 99; usage дня 200, месяца 500. Расхождение с кодом — в таблице ниже. | integration | Комбинация лимитов/баланса, фактическая SQL-проверка при достижении этой ветки и состояние БД после отказа. |
| P-21 | статус | DECLINED, declineReason=CARD_INACTIVE; резерв 0; баланс 101; usage дня 200, месяца 500. | integration | Проверяются приоритет бизнес-отказа и отсутствие побочных изменений в БД. |
| P-22 | одобрение | APPROVED, responseCode=00; резерв 100; баланс 1; usage дня 300, месяца 600. | integration | Попарная комбинация проверяется вместе с сохранением резерва и увеличением usage. |

### Card Management — попарный набор

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| CP-01 | создание карты | HTTP 201; expiryDate=0929, dailyLimit=15000000, monthlyLimit=300000000, availableBalance=100000000; общие проверки успешного создания пройдены. Расхождение с кодом — в таблице ниже. | integration | POST и последующий GET должны возвращать сохранённые поля; проверяется новая строка в БД. |
| CP-02 | создание карты | HTTP 201; expiryDate=1229, dailyLimit=1200, monthlyLimit=1500, availableBalance=0; общие проверки успешного создания пройдены. | integration | POST и последующий GET должны возвращать сохранённые поля; проверяется новая строка в БД. |
| CP-03 | создание карты | HTTP 201; expiryDate=0130, dailyLimit=1200, monthlyLimit=1500, availableBalance=1000; общие проверки успешного создания пройдены. | integration | POST и последующий GET должны возвращать сохранённые поля; проверяется новая строка в БД. |
| CP-04 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-05 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-06 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-07 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. Расхождение с кодом — в таблице ниже. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-08 | неверная валюта | Невалидное поле (валюта): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-09 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. Расхождение с кодом — в таблице ниже. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-10 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-11 | неверная валюта | Невалидное поле (валюта): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-12 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-13 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-14 | неверная валюта | Невалидное поле (валюта): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-15 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. Расхождение с кодом — в таблице ниже. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-16 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-17 | неверный BIN | Невалидное поле (BIN): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-18 | неверное имя | Невалидное поле (имя): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |
| CP-19 | неверная валюта | Невалидное поле (валюта): HTTP 400; число карт неизменно. | integration | Проверяются HTTP 400 и неизменное число строк, а не только результат DTO-валидатора. |

### Расхождения требований и реализации

Ожидаемые результаты исходных кейсов сохранены. Следующие расхождения установлены чтением кода; результаты выполнения тестов здесь не приводятся.

| Кейсы | Ожидается | Текущая реализация |
|---|---|---|
| A-18 | 05 / ISSUER_TIMEOUT при недоступном CMS | [AuthServiceImpl][auth] возвращает SERVICE_UNAVAILABLE, а [контроллер][authcontroller] — HTTP 503. |
| A-19 | Сумма 0 отклонена прямым Authorization до reserve | В [AuthorizationRequest][authrequest] стоит `@PositiveOrZero`. Проверка `>0` существует на [Gateway][validator], но не на этом прямом входе. |
| P-14, P-20 | Код 61 при одновременно недостаточных лимитах и балансе | [authorize()][auth] проверяет баланс раньше SQL-лимитов и возвращает 51. |
| CP-01 | Значения по умолчанию 15000000 / 300000000 / 100000000 | [createCard()][cardservice] передаёт null без подстановки; в [настройках][cmsconfig] default balance — 1000000, а [схема БД][cardmigration] требует NOT NULL. |
| CP-07, CP-09, CP-15 | HTTP 400 для имени из 256 символов | В [CreateCardRequest][createrequest] нет ограничения длины 255; в [БД][cardmigration] поле ограничено VARCHAR(255). |
| N-14 | Confirm в пределах 2 с, при timeout — reversal `0400` | [LoggerClient][loggerclient] не ждёт confirm; [AuthorizationClient][authclient] отправляет HTTP RollbackRequest без MTI. См. [А4](architecture/04-sequence.md). |
| N-15 | При успешной компенсации восстановлены баланс и usage | [AuthServiceImpl.rollback()][auth] вызывает CMS, но не уменьшает usage. |
| N-18, N-21 | Три обработки consumer, затем DLQ | Есть `x-max-delivery=3`, но нет явной настройки трёх попыток listener retry. См. [А5](architecture/05-rabbitmq.md). |

Привязка старых кейсов к коду: A/P — [AuthServiceImpl][auth], [LimitUsageRepository][limits], [CMS client][cmsclient]; C/CP — [CardController][cardcontroller], [CardServiceImpl][cardservice], [CardEntity][cardentity], [CreateCardRequest][createrequest], [LuhnValidator][luhn].

## Часть Г — анализ пропорций

Каждый ID учитывается один раз. Параметры внутри кейса не увеличивают его вес. Подсчитываются спроектированные сценарии, не реализованные автотесты.

| Уровень | Старые (пр. 2) | Новые (часть Б) | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 0 | 4 | 4 | 4,08% |
| api | 2 | 4 | 6 | 6,12% |
| integration | 74 | 12 | 86 | 87,76% |
| e2e | 0 | 2 | 2 | 2,04% |
| **Итого** | **76** | **22** | **98** | **100,00%** |

Формула: `доля = число кейсов уровня / 98 × 100%`. Средний слой api + integration: 92 кейса, 93,88%.

### Объяснение распределения

1. Большая доля integration обусловлена содержанием практики 2: большинство кейсов проверяет HTTP-операцию вместе с состоянием БД
2. Новые интеграционные кейсы покрывают отдельные риски микросервисов: SQL-периоды, конкуренцию резервов, атомарность outbox, доставку, confirm и DLQ. Unit остаются на уровне решений и локальной политики повторов.
3. E2E нужны только для двух полных результатов: покупка с журналом и Dashboard, отказ после блокировки
4. HTTP-контракты проверяются шестью отдельными API-кейсами и внутри интеграционных сценариев. Отдельно проверяются внешний Gateway и внутренние ответы Authorization/CMS
5. В целом на работе у меня +- так же выходит по покрытию. Стоимость интеграционников можно частично решить единый поднятием контекста

[switchtz]: ../../tz/03-switch.md
[routing]: ../../services/switch/src/main/java/com/processing/service/RoutingService.java
[route]: ../../services/switch/src/main/java/com/processing/service/RouteService.java
[authtz]: ../../tz/04-authorization.md
[auth]: ../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java
[outbox]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java
[cmsconfig]: ../../services/card-management/src/main/resources/application.properties
[gatewaytz]: ../../tz/02-gateway.md
[validator]: ../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionRequestValidator.java
[validation]: ../../services/gateway/src/main/java/com/processing/gateway/validation/TransactionValidationFilter.java
[gatewayroutes]: ../../services/gateway/src/main/resources/application.yml
[authcontroller]: ../../services/authorization/src/main/java/com/processing/authorization/controller/AuthControllerImpl.java
[declines]: ../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java
[cmstz]: ../../tz/05-card-management.md
[patchrequest]: ../../services/common/src/main/java/com/processing/common/dto/cardmanagement/PatchCardRequest.java
[cardcontroller]: ../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java
[limits]: ../../services/authorization/src/main/java/com/processing/authorization/repositories/LimitUsageRepository.java
[cardservice]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java
[logtz]: ../../tz/08-transaction-logger.md
[loglistener]: ../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java
[logservice]: ../../services/transaction-logger/src/main/java/com/processing/transactionlogger/service/TransactionService.java
[loggerclient]: ../../services/switch/src/main/java/com/processing/service/LoggerClient.java
[authclient]: ../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java
[runner]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/TransactionRunnerImpl.java
[notifylistener]: ../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java
[swrabbit]: ../../services/switch/src/main/java/com/processing/config/RabbitMQConfig.java
[notifyrabbit]: ../../services/notification-service/src/main/java/com/processing/notification/config/RabbitMQConfig.java
[uitz]: ../../tz/09-web-dashboard.md
[authrequest]: ../../services/common/src/main/java/com/processing/common/dto/authorization/AuthorizationRequest.java
[createrequest]: ../../services/common/src/main/java/com/processing/common/dto/cardmanagement/CreateCardRequest.java
[cardentity]: ../../services/card-management/src/main/java/com/processing/cardmanagement/models/CardEntity.java
[cardmigration]: ../../services/card-management/src/main/resources/db/migration/V5.1__init_cards_table.sql
[card]: ../../services/card-management/src/main/java/com/processing/cardmanagement/models/Card.java
[cmsclient]: ../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java
[luhn]: ../../services/card-management/src/main/java/com/processing/cardmanagement/services/LuhnValidator.java
[cardjpa]: ../../services/card-management/src/main/java/com/processing/cardmanagement/repositories/CardJpaRepository.java
[ttl]: https://www.rabbitmq.com/docs/3.13/ttl
