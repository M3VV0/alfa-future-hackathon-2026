# Проверка backend-версии — 20.10.2026

Проверено на macOS arm64, JDK 21, Maven Wrapper 3.9.9 и Docker Desktop 27.3.1 / Compose 2.29.7.

- `./mvnw verify`: 24 теста, без пропусков. PostgreSQL 17.6 и Garage 2.1.0 — настоящие Testcontainers. Credit и assessment используют ограниченные роли с тем же разделением схем, что и Compose.
- `docker compose --env-file .env.example up --build -d --wait`: собраны и запущены оба Java-артефакта, API/worker процессы, локальные зависимости и gateway; readiness успешен. Runtime Java 21.0.8.
- `python3 demo/smoke.py --env-file .env.example --check-restart`: PASS. Настоящие session/CSRF/login, multipart → Garage → download, создание/повтор submit, остановка/возобновление credit-worker, асинхронный assessment и callback, сохранённый REJECTED, NEEDS_INFO → revision 2 → REJECTED, закрытые внутренние маршруты, logout.
- Экспорт springdoc AssessmentResult сверяется с версионированной OpenAPI-схемой; реальная login-форма и JDBC-сессия проверяются отдельно от тестовой аутентификации.

Проверка backend-only. Бизнес-страницы возвращают 501, фронтенд не реализован по запросу пользователя. Положительный пользовательский путь невозможен со штатным временным скорером; offer/contract/simulated ledger проверены тестовым assessment. Ресурсная нагрузка, реальный kill JVM (вместо persisted dead heartbeat), backup/restore drill, CustDev, browser E2E и внешнее развёртывание не заявляются как выполненные.
