CREATE TABLE "CreditApplication"
(
    "id"                      UUID PRIMARY KEY,
    "userId"                  UUID           NOT NULL REFERENCES "User" ("id"),
    "businessId"              UUID           NOT NULL REFERENCES "Business" ("id"),
    "creationTime"            TIMESTAMPTZ    NOT NULL,
    "requestedAmount"         NUMERIC(17, 2) NOT NULL,
    "termMonths"              INTEGER        NOT NULL,
    "paymentFrequency"        VARCHAR(16)    NOT NULL,
    "financingPurpose"        VARCHAR(32)    NOT NULL,
    "declaredCollateralValue" NUMERIC(17, 2),
    "status"                  VARCHAR(16)    NOT NULL,

    CONSTRAINT "CreditApplicationRequestedAmountCheck" CHECK ("requestedAmount" > 0),
    CONSTRAINT "CreditApplicationTermMonthsCheck" CHECK ("termMonths" > 0),
    CONSTRAINT "CreditApplicationPaymentFrequencyCheck" CHECK (
        "paymentFrequency" IN ('MONTHLY', 'QUARTERLY')
        ),
    CONSTRAINT "CreditApplicationFinancingPurposeCheck" CHECK (
        "financingPurpose" IN (
                               'WORKING_CAPITAL', 'EQUIPMENT', 'INVESTMENT',
                               'REFINANCING', 'BUSINESS_AUTO', 'COMMERCIAL_MORTGAGE'
            )
        ),
    CONSTRAINT "CreditApplicationDeclaredCollateralValueCheck" CHECK (
        "declaredCollateralValue" >= 0
        ),
    CONSTRAINT "CreditApplicationStatusCheck" CHECK (
        "status" IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
        )
);

CREATE INDEX "CreditApplicationUserIdIndex" ON "CreditApplication" ("userId");
CREATE INDEX "CreditApplicationBusinessIdIndex" ON "CreditApplication" ("businessId");

CREATE TABLE "CreditApplicationTask"
(
    "applicationId" UUID PRIMARY KEY
        REFERENCES "CreditApplication" ("id") ON DELETE CASCADE,
    "creationTime"  TIMESTAMPTZ NOT NULL,
    "status"        VARCHAR(16) NOT NULL DEFAULT 'PENDING',

    CONSTRAINT "CreditApplicationTaskStatusCheck" CHECK (
        "status" IN ('PENDING', 'PROCESSING', 'AWAITING_RESULT')
        )
);

CREATE INDEX "CreditApplicationTaskQueueIndex"
    ON "CreditApplicationTask" ("status", "creationTime", "applicationId");
