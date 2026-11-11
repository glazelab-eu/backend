-- Opt-in error reports (Group 32, backend guide "Error reports"). The choice is per account and off by default: an
-- account sends nothing until it turns diagnostics_opt_in on (at sign-up or on the account page); turning it off
-- deletes the account's reports.
ALTER TABLE app_user ADD COLUMN diagnostics_opt_in BOOLEAN DEFAULT FALSE NOT NULL;
-- One reported event of an opted-in account, as the app sent it and the server scrubbed it (model.ReportScrubber: no
-- email addresses, token-shaped strings, query strings or URL fragments), with the device facts of its batch. Linked to
-- the account by its ID only (no address, name or IP is stored); it goes with the account (ON DELETE CASCADE) and is
-- purged after the retention (glazelab.reports.retention, 30 days, promised by the terms). occurred_at is the device's
-- time (the receive time when the device clock is far off), occurrences how often the app saw it before sending.
CREATE TABLE client_report (
    id UUID CONSTRAINT pk_client_report PRIMARY KEY,
    user_id UUID NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(20) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    stack VARCHAR(4000),
    occurrences INTEGER NOT NULL,
    build_label VARCHAR(64) NOT NULL,
    route_name VARCHAR(40),
    browser VARCHAR(40),
    browser_version VARCHAR(30),
    os VARCHAR(40),
    os_version VARCHAR(30),
    screen_width INTEGER,
    screen_height INTEGER,
    viewport_width INTEGER,
    viewport_height INTEGER,
    pixel_ratio DOUBLE PRECISION,
    pointer VARCHAR(10),
    language VARCHAR(35),
    standalone BOOLEAN,
    online BOOLEAN,
    connection_type VARCHAR(10),
    service_worker VARCHAR(12),
    update_pending BOOLEAN,
    reduced_motion BOOLEAN,
    color_scheme VARCHAR(10),
    timezone_offset_minutes INTEGER,
    device_memory_gb DOUBLE PRECISION,
    CONSTRAINT ck_client_report_kind CHECK (kind IN ('ERROR', 'WARNING', 'UNHANDLED_REJECTION')),
    CONSTRAINT ck_client_report_occurrences CHECK (occurrences BETWEEN 1 AND 1000),
    CONSTRAINT ck_client_report_pointer CHECK (pointer IN ('COARSE', 'FINE', 'NONE')),
    CONSTRAINT ck_client_report_service_worker CHECK (service_worker IN ('UNSUPPORTED', 'NONE', 'ACTIVE')),
    CONSTRAINT ck_client_report_color_scheme CHECK (color_scheme IN ('LIGHT', 'DARK')),
    CONSTRAINT fk_client_report_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
-- The purge (received_at before the cutoff) and the site admins' listing (newest first, paged by time and ID).
CREATE INDEX client_report_time_idx ON client_report (received_at, id);
-- Opt-out, account deletion (the cascade) and the per-account daily cap.
CREATE INDEX client_report_user_idx ON client_report (user_id, received_at);
-- The admins' summary: one group per fingerprint with its first and last time.
CREATE INDEX client_report_fingerprint_idx ON client_report (fingerprint, received_at);
-- Data minimisation of bans, admin log entries and profile-picture evidence (34-7, user decisions 2026-11-01; backend
-- guide "Bans" and "Profile pictures"). A blocklist entry keeps only the pseudonymous fingerprint of the address (the
-- keyed hash), the ban date and the short reason: no name at ban time and no banning admin (the admin and the banned
-- account are named only by the admin log, which keeps its rows for 90 days, glazelab.admin.action-retention).
ALTER TABLE email_block DROP CONSTRAINT fk_email_block_banned_by;
DROP INDEX email_block_banned_by_idx;
ALTER TABLE email_block DROP COLUMN banned_by;
ALTER TABLE email_block DROP COLUMN display_name;
-- CLOSED: a report closed without a decision because the picture went with its account (account deletion, a ban
-- without blockPictures, the pending purge): its picture and evidence copy are deleted then, so nobody can decide it.
ALTER TABLE avatar_report DROP CONSTRAINT ck_avatar_report_status;
ALTER TABLE avatar_report ADD CONSTRAINT ck_avatar_report_status CHECK (status IN ('OPEN', 'DISMISSED', 'ACTIONED', 'CLOSED'));
-- Reports about a picture of a deleted account keep only their status and dates: the reporter's text may name the owner
-- (owner_id is set on every report and nulled only by the account's deletion). Their evidence copies are deleted by the
-- next evidence purge (AvatarService.purgeEvidence); the fingerprints stay.
UPDATE avatar_report SET reason = NULL WHERE owner_id IS NULL;
UPDATE avatar_report SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP WHERE owner_id IS NULL AND status = 'OPEN';
