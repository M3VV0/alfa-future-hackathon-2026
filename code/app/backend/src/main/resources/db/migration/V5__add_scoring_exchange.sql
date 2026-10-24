ALTER TABLE "CreditApplication"
    ADD COLUMN "scoredResult" JSONB,
    ADD COLUMN "scoredAt" TIMESTAMPTZ;

ALTER TABLE "CreditApplicationTask"
    ADD COLUMN "requestPayload" JSONB,
    ADD COLUMN "attempts" INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN "nextAttemptAt" TIMESTAMPTZ,
    ADD COLUMN "updatedAt" TIMESTAMPTZ,
    ADD COLUMN "lastError" VARCHAR(64);

UPDATE "CreditApplicationTask"
   SET "nextAttemptAt" = "creationTime", "updatedAt" = "creationTime";

ALTER TABLE "CreditApplicationTask"
    ALTER COLUMN "nextAttemptAt" SET NOT NULL,
    ALTER COLUMN "updatedAt" SET NOT NULL,
    ADD CONSTRAINT "CreditApplicationTaskAttemptsCheck" CHECK ("attempts" >= 0),
    DROP CONSTRAINT "CreditApplicationTaskStatusCheck";

ALTER TABLE "CreditApplicationTask"
    ADD CONSTRAINT "CreditApplicationTaskStatusCheck"
        CHECK ("status" IN ('PENDING', 'PROCESSING', 'AWAITING_RESULT', 'COMPLETED', 'FAILED'));

CREATE INDEX "CreditApplicationTaskReadyIndex"
    ON "CreditApplicationTask" ("nextAttemptAt", "creationTime", "applicationId")
    WHERE "status" IN ('PENDING', 'PROCESSING', 'AWAITING_RESULT');
