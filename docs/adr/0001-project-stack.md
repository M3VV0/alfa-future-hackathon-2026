# ADR-0001: стек основного MVP

Дата: 20.10.2026. Статус: принято пользователем.

## Контекст

Общий язык backend-команды — Java; опыта с отдельным frontend-приложением мало. Пользователь выбрал ранее обсуждавшийся стек со Spring и frontend на Thymeleaf. Для миграций выбран Flyway. Решение должно запускаться локально в Docker, сохранять данные и асинхронные задания и не зависеть от платных сервисов.

Это решение о технологиях будущей реализации. Каркас приложения, зависимости и Docker-конфигурация этим ADR не создаются. Требования кейса находятся в [requirements.md](../../requirements.md), архитектура — в [архитектурной спецификации](../architecture/architecture.md).

## Принятый стек

| Область | Выбор | Роль в проекте |
| --- | --- | --- |
| Язык и сборка | Java 21 LTS, Maven Wrapper | Общий язык backend, workers и строгого скоринга; одна кодовая база с модулями |
| Web | Spring Boot, Spring MVC, Bean Validation | Один набор MVC-контроллеров для страниц, форм и HTMX; вызов application services |
| Frontend | Thymeleaf + HTMX + Bootstrap | Серверные страницы, формы, ошибки, документы, история и обновление статуса без SPA |
| Бизнес-данные | PostgreSQL | Схемы core/auth/assessment, неизменяемые снимки, решения, история и идемпотентность |
| Доступ к данным | Spring Data JPA + Hibernate; JDBC для специальных запросов | Доменные сущности и транзакции; без требования писать все SQL через ORM |
| Миграции | Flyway | Версионированные SQL-миграции, включая таблицы Spring Session и db-scheduler |
| Auth | Spring Security + Spring Session JDBC | Браузерная сессия в PostgreSQL, проверка membership и CSRF-защита |
| Асинхронные задания | db-scheduler + PostgreSQL | Сохраняемые задачи, heartbeat/recovery, retries и отдельные worker-процессы |
| Скоринг | Java `RulesScorer` за интерфейсом `RiskScorer` | В MVP строгие правила; единый контракт для будущего `LLMScorer` |
| Документы | Garage + AWS SDK for Java v2, интерфейс `DocumentStorage` | Локальное S3-совместимое хранилище и смена endpoint через конфигурацию |
| Локальные интеграции | WireMock + версионированные JSON-фикстуры | Синтетические ответы БКИ, реестров и ФНС, включая отказ и задержку |
| API-документация | OpenAPI + Swagger UI; springdoc для внутренних REST-контроллеров | Web HTTP-контракты form/HTML и внутренние JSON-контракты; отдельный клиентский API не создаётся |
| Наблюдаемость | Spring Boot Actuator + Micrometer + структурированные логи | Health/readiness, метрики очереди, ошибок и длительности этапов |
| Проверки | JUnit через Spring Boot Test, Testcontainers, Playwright | Unit/contract, интеграции с PostgreSQL/S3 и сквозные браузерные сценарии |
| Запуск | Docker Compose; Nginx для единого входа | Один web-origin, постоянные volumes, отдельные процессы web и workers |

## Устройство кодовой базы и процессов

- Одна Maven-кодовая база: общие контракты, credit-модули и assessment-модули.
- `credit-app`: Thymeleaf, MVC-контроллеры, auth, application services, доступ к core/auth и внутренний callback. Единственный клиентский вход — web; MVC напрямую вызывает Java services. Публичного JSON API-дубля нет.
- `credit-worker`: отдельный процесс из той же кодовой базы для проверок, обогащения, dispatch и кредитного решения. Можно использовать тот же артефакт с отдельным Spring profile.
- `assessment-app` и `assessment-worker`: Java-процессы с контрактами `/request` и `/internal/processed`, своими заданиями и схемой assessment. В MVP выполняют строгие правила.
- Auth — модуль `credit-app`, а не дополнительный независимо развёртываемый сервис MVP.
- WireMock и Garage — локальные контейнеры. PostgreSQL — один экземпляр с отдельными схемами и DB-ролями.

## Thymeleaf, HTTP и авторизация

HTML-страницы и HTMX-фрагменты используют маршруты `/app/**`; вход — `GET/POST /login`, выход — `POST /logout`. У каждой клиентской операции один web-маршрут. Отдельный клиентский JSON API не входит в проект. Полный HTTP-контракт и основания решения описаны в [ADR-0002](0002-web-only-entrypoint.md).

Thymeleaf выводит CSRF-токен в формы; HTMX отправляет его также в заголовке при изменяющих запросах. MVC возвращает страницу/redirect либо HTML-фрагмент. Статус обновляется polling HTMX каждые 2-5 секунд. Формы используют скрытые version и command_id для защиты от конфликтов и повторов.

Для браузерного сценария используется серверная сессия Spring Security. Cookie — HttpOnly, SameSite, Secure при HTTPS; при входе меняется идентификатор сессии, при выходе сессия инвалидируется. Spring Session JDBC хранит сессии централизованно, поэтому экземпляры приложения не требуют sticky sessions. JWT и refresh в текущем MVP не используются.

OpenAPI описывает реальные web HTTP-контракты и внутренние API. Коллекция web-запросов читает login-форму, получает CSRF и сохраняет cookie. Внутренние сервисы используют отдельные credentials и сетевые ограничения; их JSON endpoints не публикуются через клиентский Nginx. CSRF-исключения ограничены внутренними service-auth endpoints.

## Гарантии асинхронной обработки

`db-scheduler` является единственным механизмом исполнения сохранённых заданий; отдельный самописный polling по доменной таблице `Job` не добавляется. Его `scheduled_tasks` хранит очередь, heartbeat, время исполнения и данные повторов; доменный `Job` хранит связь с заявкой, revision, request_id, итоговый статус и диагностическую историю.

Изменение заявки и постановка задания выполняются в одной транзакции через участвующий в ней DataSource. При использовании JPA и JDBC нужно проверить, что они используют одну транзакцию и одно соединение; это отдельный интеграционный тест commit/rollback. Расписание заданий не переносится в `afterCommit`, поскольку это создаёт окно потери задания.

Сохраняются idempotency keys, inbox дедупликации, revision/version checks, retry с пределом и терминальный технический статус FAILED. Heartbeat и восстановление после падения настраиваются средствами db-scheduler и проверяются остановкой worker. RabbitMQ, Redis и Kubernetes не нужны для запуска базового MVP.

## Сменяемое хранилище документов

Бизнес-логика вызывает `DocumentStorage.put/get/delete`; реализация использует S3 SDK. В БД сохраняются `object_key`, checksum, метаданные и владелец; endpoint задаётся конфигурацией, не записывается как постоянный URL документа.

Для локальной и серверной демонстрации используется Garage с постоянными volumes. Подключение другого S3-совместимого хранилища задаётся через endpoint, bucket, region, credentials и path-style. Совместимость базовых операций проверяется тестами. При смене хранилища старые объекты нужно скопировать с сохранением ключей; смена endpoint сама по себе данные не переносит. Проверяемая версия автономна и не требует платного облачного bucket.

## Приоритеты MVP и версии

- Основной MVP полностью работает при `ASSISTANT_ENABLED=false` и `SCORING_PROVIDER=rules`.
- Сопровождение подачи заявки и `LLMScorer` — независимые опции только после готовности остальных частей MVP.
- Конкретная LLM, её runtime и Python-сервис не входят в выбранный обязательный стек.
- Точные версии Spring Boot, зависимостей, wrapper и Docker-образов фиксируются при создании рабочего каркаса. Выбирается стабильная совместимая линия с поддержкой Java 21; springdoc, db-scheduler starter и Spring Session должны соответствовать выбранной линии Boot.
- Зависимости Spring управляются BOM; остальные имеют явные версии. Flyway-миграции обеспечивают управление схемой; `ddl-auto=validate`, а не создание схемы Hibernate при старте.
- Репозиторий не должен заявлять воспроизводимость, пока каркас не собран и не проверен на зафиксированных версиях.

## Основания и последствия

Стек использует общий язык команды и исключает обязательное освоение React и отдельного клиентского состояния. HTMX отвечает за частичные обновления, а состояние кредитного процесса хранит backend. Совместное использование PostgreSQL для домена, сессий и задач сокращает число инфраструктурных компонентов, сохраняя разделение таблиц, ролей и процессов.

Полноценный кластер хранилища, отдельный auth-сервис, Kubernetes, ручной банковский интерфейс и LLM могут развиваться независимо от основного сценария. Публичный интеграционный API не входит в текущий объём; для него потребуется отдельное будущее решение. Внутренние JSON-контракты и бизнес-логика сохраняются.

## Первичные источники

- [Spring Boot: совместимость Java и инструментов сборки](https://docs.spring.io/spring-boot/system-requirements.html).
- [Spring MVC и Thymeleaf](https://spring.io/guides/gs/serving-web-content), [HTMX](https://htmx.org/docs/).
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html), [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html).
- [db-scheduler: Spring Boot, транзакции, PostgreSQL и recovery](https://github.com/kagkarlsson/db-scheduler).
- [Garage: локальный запуск](https://garagehq.deuxfleurs.fr/documentation/quick-start/), [S3 compatibility](https://garagehq.deuxfleurs.fr/documentation/reference-manual/s3-compatibility/).
- [AWS SDK for Java: endpoint configuration](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/endpoint-config.html), [springdoc-openapi](https://springdoc.org/).
