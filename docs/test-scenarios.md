# Проверка MVP

## Автоматический сквозной сценарий

После `docker compose --profile app up --build -d --wait`:

```sh
python3 scripts/verify_mvp.py
```

Нужен только Python 3 стандартной библиотеки. Без Python на хосте можно выполнить внутри
поставляемого контейнера (Bash):

```sh
docker compose exec -T scorer python - --base-url http://app:8080 --scorer-url http://localhost:8000 < scripts/verify_mvp.py
```

В PowerShell вместо перенаправления используйте `Get-Content -Raw scripts/verify_mvp.py | docker compose exec -T scorer python - --base-url http://app:8080 --scorer-url http://localhost:8000`.

 Используются реальные Java, PostgreSQL, CatBoost и
HTTP callback. Скрипт не подменяет ответы модели и не меняет БД напрямую. Входы синтетические.
Для портов другого стенда передайте `--base-url` и `--scorer-url`.

| Сценарий | Действия | Ожидаемый результат |
| --- | --- | --- |
| A / полный путь | owner; оборотный кредит 1 млн / 24 мес.; выручка 1 млн, расходы 600 тыс., текущий платёж 10 тыс.; отчёт approved.json | OFFERED на запрошенную сумму → ACCEPTED → SIGNED_SIMULATED → DISBURSED_SIMULATED |
| B / модель | Те же сведения компании; 5 млн / 6 мес., без обеспечения | Реальный высокий прогноз → REJECTED / SCORER_DECLINED |
| C / дополнение | Отправить без финансов/отчёта; затем изменить черновик и загрузить отчёт | NEEDS_INFO → новая revision → OFFERED, UUID сохраняется |
| D / иные условия | Как A, но выручка 100 тыс., расходы 60 тыс., текущий платёж 0; reduced.json | Та же оценка ML, но меньшая сумма Java, TERMS_ADJUSTED |
| E / нагрузка | Как A, но выручка 100 тыс., расходы 90 тыс., текущий платёж 10 тыс.; unaffordable.json | REJECTED / INSUFFICIENT_INCOME при положительном ML-исходе |
| F / доступ | viewer читает и пытается принять; other читает чужую заявку/отчёт | viewer: чтение 200, изменение 403; другая компания: 404 |
| G / повтор | Повтор создания с прежним ключом и вводом; повтор каждой команды принятия | Прежний UUID и отсутствие дополнительных эффектов |
| H / отмена | Отменить черновик, затем попробовать отправить | CANCELLED, отправка 409; поздний callback не открывает отменённую заявку |
| I / безопасность | Клиентский POST без CSRF; callback без сервисного токена | 403 и 401 соответственно |

Значения предназначены для свежей seed-базы. Seed не переписывает уже существующие профили: при
изменённой вручную компании прогноз может отличаться. Для чистой проверки используйте отдельное
имя Compose-проекта и свободные порты; не удаляйте рабочую БД ради демонстрации.

## Перезапуск

Скрипт сохраняет ID/ожидаемые состояния в `/tmp/credit-mvp-verification.json`. После обычного
перезапуска `app` и `scorer`:

```sh
python3 scripts/verify_mvp.py --verify-snapshot /tmp/credit-mvp-verification.json
```

Должны сохраниться заявки, решения, документы и история; после повторного входа видны прежние результаты.

## Отказы компонентов и восстановление

JUnit проверяет callback до ACK, одинаковые/конфликтующие результаты, ограничения продукта, права,
таймауты, конкуренцию Java-воркеров и явный `retry` после FAILED с сохранением снимка. Признак
`dispatched` сохраняется между циклами попыток: поздний валидный ответ принимается и до повторной отправки.

Python-тесты на настоящем PostgreSQL проверяют параллельный intake, единственную обработку,
освобождение блокировок до inference/HTTP, восстановление lease, защиту от старого владельца,
ошибки/таймауты модели, ограниченные повторы доставки, постоянные 4xx и повтор сохранённого callback.
Техническая ошибка не становится кредитным отказом. Моки есть только внутри узких тестов.

```sh
POSTGRES_PORT=5433 docker compose --project-name smartcreditor-dev up -d postgres --wait
cd code/app/backend
DB_URL=jdbc:postgresql://localhost:5433/smartcreditor sh ./mvnw verify
node --test src/test/js/credit-api.test.cjs
```

Для Python из корня, после установки `code/scorer/requirements-service.lock` в отдельный venv:

```sh
SCORER_TEST_DATABASE_URL=postgresql+asyncpg://smartcreditor:localdev@localhost:5433/smartcreditor \
python -m unittest discover -s code/scorer/tests -v
```

Все интеграционные тесты создают и удаляют только свои случайные схемы. Без переменной тестовой БД
Python явно пропускает PostgreSQL-тесты. Качество модели на реальном риске этими проверками не доказывается.
