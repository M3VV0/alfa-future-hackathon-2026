# Проверяемые сценарии

`./mvnw verify` требует Docker, создаёт и удаляет независимые Testcontainers PostgreSQL и Garage. Не использует существующие demo volumes.

| Сценарий | Автоматическая проверка |
|---|---|
| Полная заявка → проверки → обогащение → запрос assessment → callback → отказ | CreditIntegrationTest.fullRejectionAndDuplicateCallback; demo/smoke.py на Compose |
| Скорер всегда отклоняет произвольный input, одинаковый request даёт одинаковый event/result | AlwaysRejectScorerTest |
| Request → score → durable callback; сохранённый результат для reconciliation | AssessmentIntegrationTest.durableRequestScoreCallbackAndReconciliationRead |
| Отсутствует отчёт → NEEDS_INFO → загрузка → revision 2 | CreditIntegrationTest.needsInfoResubmissionKeepsFrozenRevision |
| Повтор submit / concurrent create; конфликт ключа и version | CreditIntegrationTest.submitIsIdempotentAndVersioned / concurrentDuplicateCreateHasOneEffect |
| JPA/JDBC/db-scheduler одно соединение, общий rollback и commit | CreditIntegrationTest.jpaAndJdbcSchedulerUseOneConnectionAndRollbackAtomically |
| Rollback assessment request и очереди | AssessmentIntegrationTest.queueAndRequestRollbackTogether |
| Пропущенный/поддельный service token; CSRF; истёкшая HTMX session; чужие компании/заявки/документы/предложения/договоры | CreditIntegrationTest.ownershipCsrfAndInternalAuthentication / offersAndSimulationsAreIdempotentWithAlternateTestAssessment; AssessmentIntegrationTest.onlyServiceCredentialsAccepted |
| Несовпадающий hash / request payload / риск / callback payload | CreditIntegrationTest.requestMismatchAndInvalidRiskAreRejected / fullRejectionAndDuplicateCallback; AssessmentIntegrationTest.requestPayloadConflictAndHashValidation |
| Интеграция недоступна 6 попыток → технический FAILED, replay | CreditIntegrationTest.exhaustedIntegrationRetriesAreTechnicalFailureAndReplayable |
| Задача оставлена погибшим worker с устаревшим heartbeat → новый scheduler исполняет один раз | SchedulerRecoveryIntegrationTest |
| Настоящий S3 Garage put/get/delete, новый адаптер читает прежний ключ | S3StorageIntegrationTest |
| Аннуитет, нулевая ставка, округление контрпредложения 2 603 000 ₽ | DecisionPolicyTest |
| Принять предложение → подписать → выдать (имитация), повтор не дублирует ledger | CreditIntegrationTest.offersAndSimulationsAreIdempotentWithAlternateTestAssessment |
| Сигнатура/имя файла, checksum скачивания; профиль с version | CreditIntegrationTest.filesValidateSignaturesAndChecksums / profileUpdatesAreVersionedAndDoNotRewriteSnapshot |

Тест восстановления воспроизводит persisted claimed state после аварийной остановки; реальное убийство JVM дополнительно проверяется на Compose остановкой worker. Сквозной smoke использует реальные workers и сеть. Интерфейс проверен рендерингом всех страниц/фрагментов и реальными браузерными действиями. CustDev и ресурсные нагрузки отдельно не проводились.

Сценарии A/D/E/F целевой архитектуры с будущим скорером пока заканчиваются отказом заглушки. Сценарий B может отклоняться самостоятельным стоп-фактором просрочки. Сценарий C сначала запрашивает сведения, после дополнения получает отказ. Ожидаемые одобрения исходной спецификации не относятся к этой временной версии; это зафиксировано ADR-0003.

Дополнительно: CreditIntegrationTest.realLoginUsesCsrfAndJdbcSession проверяет реальную встроенную форму, redirect и JDBC cookie session; exportedCallbackSchemaMatchesVersionedContract сверяет свойства экспортированной DTO со статической OpenAPI. Выполненная проверка и её границы: [verification.md](verification.md).

## Проверки веб-интерфейса

- `browserPagesAndIndependentCommandKeysRender`: все основные страницы, локальные assets и отдельные command_id каждой формы.
- `rubleFormConvertsExactlyAndRetainsInvalidInput`: рубли с разделителями/копейками, 422, сохранение текста и отсутствие изменения БД при ошибке.
- `statusFragmentsPollOnlyWhileProcessingAndExplainMissingInformation`: polling 3 секунды, остановка на NEEDS_INFO и адресная подсказка.
- `documentUploadFragmentAndSavedReportUseAuthorizedApplication`: отчёт из сохранённых финансов, HTML-панель загрузки, выбранный файл и запрет чужого доступа.
- Положительный доменный тест дополнительно рендерит offer и contract без изменения штатного скорера.

Ручной браузерный сценарий и воспроизводимые шаги: `demo/walkthrough.md`. Все приложения продолжают использовать единственный web transport.
