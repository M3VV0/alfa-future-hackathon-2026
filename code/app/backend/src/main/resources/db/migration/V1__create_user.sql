CREATE TABLE "User"
(
    "id"           UUID         PRIMARY KEY,
    "email"        VARCHAR(255) NOT NULL UNIQUE,
    "passwordHash" VARCHAR(255) NOT NULL,
    "creationTime" TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
