# Умный кредитный кабинет предпринимателя

Бэкенд кредитного конвейера для малого бизнеса: компании и полномочия, каталог двух продуктов, черновики, приватные документы, отправка заявки, проверки, локальное обогащение, асинхронный assessment и решение. Данные сохраняются в PostgreSQL, файлы — в Garage. Все демонстрационные данные синтетические.

**Текущий скорер всегда возвращает отказ.** `AlwaysRejectScorer` реализует `RiskScorer`, provider `rules`, model_version `stub_reject_v1`, score 105 / HIGH и `SCORER_ALWAYS_REJECT`. Это временная заглушка по запросу пользователя. Неполная заявка получает `NEEDS_INFO`, сбой интеграции — технический `FAILED`, полноценная оценённая заявка — `REJECTED`. Рабочий провайдер не выдаёт предложений; положительные ветки тестируются отдельно.

**Веб-кабинет реализован** на Thymeleaf + HTMX + Bootstrap: вход, список и фильтры заявок, каталог, формы в рублях, документы, автоматические статусы/история, запрос сведений, компании и профиль. Предложения и имитации договоров доступны соответствующим бизнес-состояниям. Bootstrap и HTMX поставляются локально; для работы интерфейса CDN и Node.js не нужны. Единственный клиентский вход — web, отдельного JSON API нет.

## Запуск

Нужны Docker Engine/Desktop с Compose v2; рекомендуются 4 CPU, 8 ГБ RAM и свободный порт 8080. Первый запуск скачивает публичные образы и Maven-зависимости. В работающем сценарии внешние API и платные ресурсы не используются.

```sh
cp .env.example .env
docker compose up --build -d --wait
```

Единственный опубликованный адрес: [localhost:8080](http://localhost:8080). [Вход](http://localhost:8080/login), [Swagger](http://localhost:8080/swagger-ui/index.html). Основной сценарий проходит внутри веб-кабинета; [пошаговая проверка](demo/walkthrough.md). В `.env.example` заведомо демонстрационные секреты; `.env` игнорируется Git.

Compose поднимает PostgreSQL, Garage, одноразовый garage-init, WireMock, credit-app, credit-worker, assessment-app, assessment-worker и Nginx. Workers используют профиль `worker`; API-процессы не исполняют очередь. `garage-init` создаёт layout, S3 key и приватный bucket и безопасно повторяется на существующих volumes. Healthchecks Java проверяют HTTP readiness после миграций. Внешние порты БД, S3, моков, workers и assessment не публикуются. Nginx закрывает `/internal`, `/request`, `/requests`, `/actuator`.

Остановка с сохранением данных: `docker compose down`. PostgreSQL и оба каталога Garage находятся в именованных volumes. Не удаляйте volumes для обычного перезапуска.

## Проверка бэкенда

Java 21 и работающий Docker:

```sh
./mvnw verify
```

Maven 3.9.9 закреплён Wrapper; остальные версии находятся в `pom.xml` и Compose. На macOS с нестандартным Docker socket при необходимости:

```sh
export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

Тесты используют настоящий PostgreSQL и Garage в Testcontainers; не пропускаются при недоступном Docker. Проверяются commit/rollback JPA + JDBC + db-scheduler на одном соединении, дубли команд/request/callback, конкуренция, корреляция и диапазоны assessment, права/CSRF, запрос сведений и revision 2, технический FAILED/replay, S3 put/get/delete, восстановление задания с мёртвым heartbeat. Ветви предложений и имитации выдачи проверяются тестовым assessment без изменения штатного скорера.

Сквозная проверка поднятого Compose (Python 3 на хосте):

```sh
python3 demo/smoke.py
```

Скрипт использует реальные login/CSRF/session/form/multipart, загружает документ в Garage, создаёт и отправляет заявку, ожидает настоящий callback и отказ. Для проверки сохранённого результата он читает локальную БД через `docker compose exec`; это техническая проверка, не публичный API. Для иного env-файла: `python3 demo/smoke.py --env-file .env.example`.

## Аккаунты и данные

| Логин | Пароль | Компания | ИНН |
|---|---|---|---|
| alice | `DEMO_PASSWORD` из .env | `20000000-0000-0000-0000-000000000001` | 9900000017 |
| bob | тот же | `20000000-0000-0000-0000-000000000002` | 9900000024 |

Seed включён только у credit-app через `SEED_ENABLED=true`; повторный запуск сохраняет существующие данные и пароли. Документы установленного формата: `demo/financial-report-1.json`, `demo/financial-report-2.json`. Они загружаются клиентской командой; seed не записывает готовые результаты заявок. Продукты: `working-capital` и `equipment`, версия 1. Моки: компания А без просрочек, компания Б с просрочкой 120 дней, неизвестный ИНН `found=false`, специальный ИНН 9900000090 даёт 503. Все успешные оценки всё равно приводят к отказу временного скорера.

Финансовые суммы и параметры кредита — **целые копейки**, валюта RUB. `100000000` означает 1 млн рублей. Ставки — доли единицы (`0.18` = 18%). Обязательный финансовый отчёт — JSON с `inn`, `period` (YYYY-MM), `revenueMinor`, `expensesMinor`. Поля должны совпадать с формой; ИНН проверяется по контрольным цифрам. PDF принимается как дополнительный `OTHER` по MIME/сигнатуре, без OCR; максимум 10 МиБ. Документы доступны только через проверку membership.

## Конфигурация

| Переменные | Назначение |
|---|---|
| POSTGRES_PASSWORD | администратор локальной БД |
| CREDIT_DB_PASSWORD, ASSESSMENT_DB_PASSWORD | отдельные роли credit (core/auth) и assessment |
| CREDIT_JDBC_URL, ASSESSMENT_JDBC_URL; *_DB_USER | JDBC и пользователи при запуске вне Compose |
| SERVICE_TOKEN | service auth заголовок X-Service-Token, минимум 32 символа |
| DEMO_PASSWORD, SEED_ENABLED | локальные аккаунты, минимум 12 символов пароля; seed выключен по умолчанию вне Compose |
| S3_ENDPOINT, S3_BUCKET, S3_REGION, S3_PATH_STYLE | сменяемое S3-хранилище; в Compose Garage/garage/path-style |
| S3_ACCESS_KEY, S3_SECRET_KEY | приватный S3 доступ |
| GARAGE_RPC_SECRET | RPC secret Garage, 64 hex-символа |
| MOCKS_URL, ASSESSMENT_URL, CREDIT_URL | внутренние URL интеграций |
| COOKIE_SECURE | true для HTTPS; false для localhost HTTP |
| SPRING_PROFILES_ACTIVE=worker | включает db-scheduler execution |

Базовая версия не содержит LLM и ассистента; нет обязательного LLM runtime. При смене S3 endpoint существующие объекты нужно перенести с сохранением object_key; ключи и checksum хранятся в БД, URL не сохраняются. AWS SDK настроен без chunked upload и необязательных новых checksum для совместимости Garage; целостность документа проверяется собственным SHA-256.

## Архитектура и материалы

- `contracts` — service DTO, `RiskScorer`; `platform` — очередь, транзакции, JSON/hash, service auth, trace, метрики.
- `credit` — JPA aggregate, application services, MVC, callback/inbox, проверки, правила решения, документы, предложения, имитации.
- `assessment` — идемпотентный `/request`, сохранённый результат, отдельные SCORE/CALLBACK jobs и временный скорер.
- [Принятая архитектура](docs/architecture/architecture.md), [стек](docs/adr/0001-project-stack.md), [web-only](docs/adr/0002-web-only-entrypoint.md), [уточнение этой версии](docs/adr/0003-backend-and-temporary-scorer.md).
- [API](docs/api.md), [OpenAPI web](openapi/web.yaml), [internal](openapi/internal.yaml), [коллекция Postman](docs/requests/collection.json), [модель данных](docs/data-model.md), [сценарии тестирования](docs/test-scenarios.md), [эксплуатация](docs/operations.md), [результаты проверки](docs/verification.md), [сторонние компоненты](THIRD_PARTY_NOTICES).

Архитектура и временный скорер зафиксированы в ADR-0001/0002/0003, реализация веб-кабинета — в [ADR-0004](docs/adr/0004-web-cabinet.md). Веб-интерфейс проверяется интеграционными тестами рендеринга и реальными браузерными сценариями. CustDev, внешний стенд и итоговый идентификатор сдаваемой версии не выдаются за завершённые работы.

## Работа с интерфейсом

Суммы на экране вводятся в рублях (допустимы пробелы, запятая или точка), сервер преобразует их в целые копейки. Создайте черновик, заполните финансы, сохраните их и скачайте шаблон отчёта в разделе документов. Загрузите JSON, отметьте его и сохраните заявку; после этого отправьте её на рассмотрение. Для сценария NEEDS_INFO сначала отправьте заявку без отчёта, затем дополните её.

Статусы и история обновляются каждые 3 секунды до завершения обработки. Редактор при этом не заменяется. Ошибки полей возвращают 422 с сохранёнными значениями, конфликт устаревшей вкладки — 409 с предложением обновить страницу. Каждая форма имеет собственный command_id и CSRF. Вход и выход используют серверную JDBC-сессию.

Интерфейс адаптируется к узким экранам, содержит видимые состояния загрузки, пустые списки, ошибки и клавиатурные фокусы. Решение временного скорера не изменено: готовые заявки получают отказ с объяснением демонстрационного ограничения; технические ошибки отображаются отдельно.
