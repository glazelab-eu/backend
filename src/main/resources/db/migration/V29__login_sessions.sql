-- Sign-ins that survive restarts, deployments and browser restarts (security.LoginSessions). The browser keeps a
-- random token in an HttpOnly cookie; a row keeps only its SHA-256 hash (so a database copy names no usable
-- session), the account and the session version the sign-in was made with, and when it started and was last
-- renewed. Nothing about the device (address, browser) is stored. A row ends at sign-out, at a password change
-- or reset, with its account (cascade) and, by the hourly purge, 30 days after its last renewal or 365 days after
-- the sign-in.
CREATE TABLE login_session (
    token_hash VARCHAR(64) CONSTRAINT pk_login_session PRIMARY KEY,
    user_id UUID NOT NULL,
    session_version INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    renewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_login_session_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
CREATE INDEX login_session_user_idx ON login_session (user_id);
