CREATE TABLE "user"
(
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    creation_time TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
