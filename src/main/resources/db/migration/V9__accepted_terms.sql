-- Existing accounts have no recorded acceptance; never infer consent retroactively.
ALTER TABLE app_user ADD COLUMN accepted_terms_version VARCHAR(32);
ALTER TABLE app_user ADD COLUMN terms_accepted_at TIMESTAMP;
ALTER TABLE app_user ADD CONSTRAINT ck_user_terms_acceptance CHECK (
    (accepted_terms_version IS NULL AND terms_accepted_at IS NULL) OR
    (accepted_terms_version IS NOT NULL AND terms_accepted_at IS NOT NULL)
);
