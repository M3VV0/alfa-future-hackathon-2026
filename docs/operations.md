# Эксплуатация локального стенда

Логи: `docker compose logs -f credit-worker assessment-worker`. JSON логи включают trace_id/job_id, тип ошибки и попытку; полные документы, секреты и payload не логируются. Trace проходит из HTTP в job и assessment request; исходящий HTTP передаёт X-Trace-Id; callback связывается с исходным trace и request_id. Внутренние `/actuator/health/liveness` и `/readiness` не требуют token, скрыты Nginx. `/actuator/prometheus` требует service token. Метрики workflow.duration, workflow.jobs, workflow.jobs.current, workflow.queue.oldest.seconds плюс метрики JVM/HTTP/пула БД.

Проверка остановки: `docker compose stop credit-worker`, отправить новую заявку технической коллекцией, убедиться, что SUBMITTED сохраняется, затем `docker compose start credit-worker`. Повторный запуск продолжает очередь. db-scheduler heartbeat каждые 5 секунд, missed limit 4, polling 1 секунда. При аварии heartbeat recovery выполняется средствами библиотеки. Тест SchedulerRecoveryIntegrationTest воспроизводит потерянный claim.

Failed jobs видны в core.job или assessment.job, ошибки — job_attempt. Технический replay выполняется из контейнерной сети с X-Service-Token через POST `/internal/jobs/{id}/replay` нужного сервиса. Исходный job остаётся FAILED для диагностики, новый имеет собственный id. Не изменять бизнес-статус заявки SQL вручную для восстановления. Reconciliation ждёт до 60 проверок по 5 секунд после начальных 15 секунд, затем bounded retries/FAILED.

## Резервная копия и восстановление

На синтетическом локальном стенде остановить пишущие процессы: `docker compose stop credit-app credit-worker assessment-app assessment-worker garage-init garage`. Снять БД: `docker compose exec -T postgres pg_dump -U postgres -d credit -Fc > credit.dump`. Сохранить volumes garage-meta/garage-data средствами Docker/вашего backup-инструмента при остановленном Garage; переносить оба каталога вместе. RPC/node metadata и ключи требуются для восстановления. Хранить `.env` отдельно с ограниченным доступом.

Для восстановления создать инфраструктуру с теми же версиями, ролями/паролями и конфигурацией. В пустую БД восстановить `pg_restore -U postgres -d credit` из dump (через docker compose exec -T), восстановить оба Garage volumes до старта сервера, затем поднять приложения и workers. Проверить readiness, ранее созданные application_revision, чтение checksum документа и продолжение очереди. Не накатывать dump поверх работающего пишущего стенда. Данная инструкция не является выполненным DR-тестом; отдельный backup/restore drill требуется перед эксплуатацией вне демо.

Базовая версия использует один PostgreSQL и одноузловой Garage без отказоустойчивой репликации. Для shared/HTTPS стенда задать новые секреты, COOKIE_SECURE=true и TLS на ingress. Не доверять X-Forwarded-* от произвольного клиента; текущий localhost стенд не включает автоматическую обработку proxy headers. Доступ жюри к GitHub и commit/tag сдачи задаёт команда при сдаче.
