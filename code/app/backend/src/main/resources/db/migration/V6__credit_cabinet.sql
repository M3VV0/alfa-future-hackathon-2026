ALTER TABLE "CreditApplicationTask" DROP CONSTRAINT "CreditApplicationTaskStatusCheck";
ALTER TABLE "CreditApplicationTask" ADD CONSTRAINT "CreditApplicationTaskStatusCheck"
    CHECK ("status" IN ('WAITING_INPUT','PENDING','PROCESSING','AWAITING_RESULT','COMPLETED','FAILED'));

CREATE TABLE "CreditDossier" (
    "applicationId" UUID PRIMARY KEY REFERENCES "CreditApplication"("id") ON DELETE CASCADE,
    "creatorId" UUID NOT NULL REFERENCES "User"("id"),
    "idempotencyKey" UUID NOT NULL,
    "inputHash" VARCHAR(64) NOT NULL,
    "productId" VARCHAR(32) NOT NULL CHECK ("productId" IN ('working-capital','equipment')),
    "state" VARCHAR(32) NOT NULL CHECK ("state" IN ('DRAFT','NEEDS_INFO','SUBMITTED','SCORING','OFFERED',
        'REJECTED','FAILED','ACCEPTED','SIGNED_SIMULATED','DISBURSED_SIMULATED','DECLINED','CANCELLED','EXPIRED')),
    "revision" INTEGER NOT NULL DEFAULT 1 CHECK ("revision" > 0),
    "financials" JSONB,
    "submissionSnapshot" JSONB,
    "decision" JSONB,
    "reasonCode" VARCHAR(64) NOT NULL,
    UNIQUE ("creatorId", "idempotencyKey")
);
CREATE INDEX "CreditDossierStateIndex" ON "CreditDossier"("state");

CREATE TABLE "CreditEvent" (
    "sequence" BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    "id" UUID PRIMARY KEY,
    "applicationId" UUID NOT NULL REFERENCES "CreditApplication"("id") ON DELETE CASCADE,
    "revision" INTEGER NOT NULL,
    "state" VARCHAR(32) NOT NULL,
    "reasonCode" VARCHAR(64) NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "snapshot" JSONB
);
CREATE INDEX "CreditEventApplicationIndex" ON "CreditEvent"("applicationId", "createdAt", "id");

-- Small prototype documents stay in PostgreSQL so the Java MVP needs no storage service.
CREATE TABLE "CreditDocument" (
    "id" UUID PRIMARY KEY,
    "applicationId" UUID NOT NULL REFERENCES "CreditApplication"("id") ON DELETE CASCADE,
    "revision" INTEGER NOT NULL,
    "content" BYTEA NOT NULL CHECK (octet_length("content") BETWEEN 1 AND 1048576),
    "checksum" VARCHAR(64) NOT NULL,
    "createdAt" TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX "CreditDocumentApplicationIndex" ON "CreditDocument"("applicationId", "revision" DESC);
