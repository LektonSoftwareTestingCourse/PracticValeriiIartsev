# Глоссарий

> Термины домена процессинга и курса — для быстрого входа агента в контекст.
> **Обновлять при изменении:** —

## Обзор

Проект смешивает банковский процессинг, тестовую инженерию и организацию курса.
Ниже — сокращения и понятия, встречающиеся в `tz/`, `docs/` и коде.

## Термины

| Термин | Расшифровка / значение |
|---|---|
| СМП | Симулятор процессингового центра — объект тестирования курса (шутка команды: «Система медленных платежей») |
| Процессинг | обработка карточных транзакций от терминала до эмитента и обратно |
| ISO 8583 | стандарт финансовых сообщений; в СМП упрощён до JSON |
| MTI | Message Type Indicator: `0100` запрос авторизации, `0110` ответ, `0400` reversal |
| PAN | Primary Account Number, номер карты (16 цифр) |
| BIN | Bank Identification Number, первые 6 цифр PAN; в СМП только «свои» BIN |
| Issuer | банк-эмитент карты (`issuerId`, напр. `ISS001`) |
| Acquirer | банк-эквайрер, обслуживающий мерчанта (`acquirerId`) |
| Switch / Router | сервис маршрутизации транзакции по BIN к эмитенту |
| Authorization | решение APPROVED/DECLINED по статусу, сроку, лимитам, балансу |
| STAN | System Trace Audit Number, 6 цифр, номер транзакции в терминале |
| RRN | Retrieval Reference Number, 12 символов, идентификатор для поиска |
| AuthCode | 6-символьный код авторизации |
| MCC | Merchant Category Code, категория мерчанта |
| processingCode | `000000` = покупка |
| Reserve / Reservation | резервирование средств карты под авторизацию |
| Rollback / Reversal | откат резервирования при сбое логирования |
| responseCode | ISO-код ответа: `00` ok, `51` insufficient funds, `96` system error и др. |
| Decline reason | текстовая причина отказа |
| LIMIT / limit_usage | учёт дневных и месячных лимитов |
| Outbox pattern | сохранение события в БД в одной транзакции + последующая публикация в брокер |
| Eventual consistency | лог транзакции появляется после доставки через очередь, не мгновенно |
| Publisher Confirms | подтверждение публикации RabbitMQ; иначе rollback + `96` |
| DLX / DLQ | dead-letter exchange / queue: retry, затем отстойник с TTL 60s |
| Luhn | алгоритм проверки контрольной суммы PAN |
| Graceful shutdown | контролируемое завершение с drain period |
| Rate limiting | token bucket на client IP для транзакционных запросов |
| Circuit breaker | размыкание вызовов downstream после серии отказов |
| Artifact / артефакт | сдаваемый результат практики N |
| Practice N | практика по номеру 1–8, label `practice-N` в Issue |
| LLM-проверка | оценка текстового артефакта по рубрике внешней LLM |
| Smoke test | быстрая авто-приёмка работоспособности стенда |
| Load smoke | нагрузочный прогон smoke-уровня |
| Coverage map | карта покрытия по уровням пирамиды тестирования |
| Backend for testing | объектный стенд, который студенты тестируют, а не разрабатывают |
| GHCR | GitHub Container Registry, namespace образов `${GHCR_USER}` |
| Студенческий репозиторий | `Practic{Имя}{Фамилия}` — изолированная копия эталона |
| Эталонный репозиторий | `LektonSoftwareTestingCourse/Practic` — источник обновлений `upstream` |

## Связи

- Домен: [07_DOMAIN_MODEL.md](07_DOMAIN_MODEL.md); архитектура: [02_ARCHITECTURE.md](02_ARCHITECTURE.md);
  тестирование: [10_TESTING.md](10_TESTING.md).
