CREATE TABLE auth_session
(
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES "user" (id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(64) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_session_valid_period CHECK (expires_at > created_at)
);

CREATE INDEX auth_session_user_id_idx ON auth_session (user_id);
