CREATE TABLE "AuthSession"
(
    "id"               UUID        PRIMARY KEY,
    "userId"           UUID        NOT NULL REFERENCES "User" ("id") ON DELETE CASCADE,
    "refreshTokenHash" VARCHAR(64) NOT NULL,
    "createdAt"        TIMESTAMPTZ NOT NULL,
    "expiresAt"        TIMESTAMPTZ NOT NULL,
    CONSTRAINT "AuthSessionValidPeriod" CHECK ("expiresAt" > "createdAt")
);

CREATE INDEX "AuthSessionUserIdIndex" ON "AuthSession" ("userId");
