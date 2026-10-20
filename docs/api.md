# HTTP-контракты

Версионированный источник контракта: `openapi/web.yaml` и `openapi/internal.yaml` (JSON является допустимым YAML). Swagger UI в credit-app читает эти файлы. Springdoc `/v3/api-docs` экспортирует внутренние REST DTO/методы; он не заменяет явную web-спецификацию.

Клиентский transport — Spring MVC, form-urlencoded/multipart, server session, CSRF. Публичных `/api/v1` и JSON-представлений бизнес-операций нет. Имена полей форм Java-style (`amountMinor`, `companyId`), идентификатор команды `command_id`. Service JSON — snake_case. Вложенный неизменяемый snapshot содержит имена доменных полей Java-style.

GET `/login` — Thymeleaf-страница входа Spring Security, выдаёт `_csrf` и session cookie. POST `/login` — username/password/_csrf, 302 на кабинет. После входа CSRF меняется, GET `/login` получает новый токен. POST `/logout` требует CSRF и инвалидирует сессию.

Изменяющие web-команды возвращают 303 Location. С HX-Request:true возвращают 200 HX-Redirect. POST `/app/documents` возвращает Location `/app/companies/{id}#document-{document_id}`. Это даёт технической коллекции идентификатор файла без дополнительного API. GET документа возвращает attachment, корректный MIME, no-store и nosniff. Остальные GET возвращают Thymeleaf-страницы или HTMX-фрагменты. Данные защищены membership до рендеринга. GET `/app/applications/new` показывает форму создания. GET `/app/applications/{id}/report-template` скачивает JSON-отчёт по сохранённым данным, с проверкой прав.

Формы команд содержат version и UUID command_id. Повтор с тем же user/command_id и payload возвращает тот же результат независимо от изменившейся version; новый payload с прежним ключом даёт 409. После изменения формы нужен новый ключ. Для создания и загрузки version не нужна. Версия приложения после создания 0; каждое изменение aggregate увеличивает её. Hidden version берётся из серверной model. Каждая форма получает отдельный UUID command_id.

Ошибки форм — 422, отсутствующий или чужой объект — 404, конфликт version/ключа/состояния — 409, CSRF — 403, временный сбой — 503. Без аутентификации обычный web-запрос направляется на /login; HTMX получает 401 и HX-Redirect. Ответы содержат X-Trace-Id. Приватные ответы no-store, варианты Vary: HX-Request. Ошибки LoanForm отображаются у полей с сохранением исходного ввода; HTMX получает 422-фрагмент редактора. Конфликты и временные ошибки показываются в панели обратной связи без замены формы.

## Внутренние сервисы

Требуют X-Service-Token; не используют браузерную сессию. Nginx их не публикует. Моки доступны только в локальной контейнерной сети.

POST assessment `/request` сохраняет request, payload hash и SCORE job одной транзакцией; только после commit возвращает 202. Повтор request_id с тем же payload безопасен, другой payload — 409. Поддерживается только provider rules/model_version stub_reject_v1. `input_hash` — SHA-256 UTF-8 JSON объекта `{input, provider, model_version, schema_version}` с рекурсивно отсортированными ключами, без пробелов; порядок массивов сохраняется. Канонизацию выполняет `platform.Json` с Jackson. Не требуется формировать этот объект клиентскому браузеру.

GET assessment `/requests/{id}` возвращает статус, callback_state и сохранённый result. Credit reconciliation использует тот же callback/inbox путь, не создаёт повторный assessment. SCORE и CALLBACK имеют отдельные durable jobs: при сбое callback готовый результат остаётся доступным.

POST credit `/internal/processed` проверяет Bean Validation, сумму факторов и risk band (0–39 LOW, 40–79 MEDIUM, 80–105 HIGH), ожидаемые request/application/revision/hash/provider/model. Схема 1. Inbox(event_id) и assessment + DECIDE job сохраняются атомарно. Точный дубликат получает 200, изменённый payload с event_id — 409, неизвестный request — 404, корреляционный конфликт — 409. Валидный поздний результат известной старой revision или уже завершённой обработки получает 200 IGNORED с аудитом.

POST `/internal/jobs/{id}/replay` доступен обоим сервисам только с service token. Создаёт новое задание из FAILED, сохраняет историю исходного. Учитывает проверки revision и актуального состояния. Штатные worker retries: первая попытка + до 5 повторов, интервалы 2/5/15/30/60 секунд.

Экранные формы используют amountRubles/revenueRubles/expensesRubles/debtRubles с точным серверным преобразованием BigDecimal в копейки. Прежние minor-поля продолжают приниматься на тех же маршрутах для коллекции. Status-фрагмент включает out-of-band обновление верхнего badge и этапов. Файл с превышением servlet-лимита может получить 413 с HTML-объяснением; browser и application checks ограничивают документ 10 МиБ. Подробности: ADR-0004.
