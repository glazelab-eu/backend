ALTER TABLE app_user ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE app_user ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;
CREATE TABLE account_token (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('RESET_PASSWORD', 'VERIFY_EMAIL')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    UNIQUE (user_id, purpose)
);
