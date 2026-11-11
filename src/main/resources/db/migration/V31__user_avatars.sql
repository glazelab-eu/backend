-- Profile pictures (service.AvatarService): at most one per account, shown to every signed-in (verified) account and
-- never to guests. Not a photo: no visibility, kind, listing or sync. The two stored objects use the photo keys
-- (<uuid>.jpg: the square of at most 256 px; <uuid>.card.jpg: at most 64 px). Replacing or removing a picture deletes
-- its row, so its ID (part of its URL) answers 404 from then on; the account takes its picture along. The hashes
-- (model.AvatarFingerprint: SHA-256 of the upload and of the stored square, a 64-bit dHash) feed the re-upload block.
CREATE TABLE user_avatar (
    id UUID CONSTRAINT pk_user_avatar PRIMARY KEY,
    user_id UUID NOT NULL,
    storage_key VARCHAR(120) NOT NULL,
    upload_sha256 VARCHAR(64) NOT NULL,
    stored_sha256 VARCHAR(64) NOT NULL,
    dhash BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_user_avatar_user UNIQUE (user_id),
    CONSTRAINT uq_user_avatar_key UNIQUE (storage_key),
    CONSTRAINT fk_user_avatar_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
-- A signed-in account's report of another account's profile picture, for the site admins' queue. It outlives its
-- picture (no foreign key on avatar_id: replaced, removed or gone with its account) and stays after a site admin
-- closed it (DISMISSED or ACTIONED, with time and admin). owner_id is the picture's account at report time. A deleted
-- owner, reporter or closing admin leaves the report without that account.
CREATE TABLE avatar_report (
    id UUID CONSTRAINT pk_avatar_report PRIMARY KEY,
    avatar_id UUID NOT NULL,
    owner_id UUID,
    reporter_id UUID,
    reason VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(10) NOT NULL,
    closed_at TIMESTAMP WITH TIME ZONE,
    closed_by UUID,
    CONSTRAINT ck_avatar_report_status CHECK (status IN ('OPEN', 'DISMISSED', 'ACTIONED')),
    CONSTRAINT ck_avatar_report_closed CHECK ((status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR (status <> 'OPEN' AND closed_at IS NOT NULL)),
    CONSTRAINT fk_avatar_report_owner FOREIGN KEY (owner_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_avatar_report_reporter FOREIGN KEY (reporter_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_avatar_report_closed_by FOREIGN KEY (closed_by) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_avatar_report_reporter UNIQUE (avatar_id, reporter_id)
);
CREATE INDEX avatar_report_time_idx ON avatar_report (created_at);
CREATE INDEX avatar_report_reporter_idx ON avatar_report (reporter_id);
CREATE INDEX avatar_report_owner_idx ON avatar_report (owner_id);
CREATE INDEX avatar_report_closed_by_idx ON avatar_report (closed_by);
-- A reported picture after it left its account (replaced, removed by its owner or a site admin, or gone with the
-- account): its ID (= avatar_report.avatar_id), how it went, its hashes and, while it is quarantined evidence, the key
-- of its two objects. The key is kept only when the picture had an open report when it went; it is cleared (and the
-- objects deleted) by the daily purge once every report of the picture has been closed for the retention, and with the
-- account. Only the site admins' report picture endpoint reads it.
CREATE TABLE avatar_evidence (
    avatar_id UUID CONSTRAINT pk_avatar_evidence PRIMARY KEY,
    storage_key VARCHAR(120),
    outcome VARCHAR(20) NOT NULL,
    upload_sha256 VARCHAR(64) NOT NULL,
    stored_sha256 VARCHAR(64) NOT NULL,
    dhash BIGINT NOT NULL,
    removed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_avatar_evidence_key UNIQUE (storage_key),
    CONSTRAINT ck_avatar_evidence_outcome CHECK (outcome IN ('REPLACED', 'OWNER_REMOVED', 'ADMIN_REMOVED', 'ACCOUNT_DELETED'))
);
-- Pictures a site admin removed: hashes only (no account, no picture ID), kept without expiry; an upload that matches
-- one is refused for every account. Lifting a block is an SQL delete by the hashes (avatar_evidence keeps them).
CREATE TABLE avatar_block (
    id UUID CONSTRAINT pk_avatar_block PRIMARY KEY,
    upload_sha256 VARCHAR(64) NOT NULL,
    stored_sha256 VARCHAR(64) NOT NULL,
    dhash BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX avatar_block_stored_idx ON avatar_block (stored_sha256);
-- Account bans (Group 34, backend guide "Bans"). A ban removes the account like an account deletion and blocks its
-- address from registering again. The address itself is never stored: email_hash is the hex HMAC-SHA-256 of the
-- normalised address (model.EmailAddresses.canonical) under the server secret GLAZELAB_BAN_SECRET, so a copy of the
-- table cannot be dictionary-tested without that secret. Registration looks it up by that one unique index. The
-- admin recognises an entry by the display name at ban time, the reason and the date; a deleted admin leaves NULL.
CREATE TABLE email_block (
    id UUID CONSTRAINT pk_email_block PRIMARY KEY,
    email_hash VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    banned_at TIMESTAMP WITH TIME ZONE NOT NULL,
    banned_by UUID,
    CONSTRAINT uq_email_block_hash UNIQUE (email_hash),
    CONSTRAINT fk_email_block_banned_by FOREIGN KEY (banned_by) REFERENCES app_user (id) ON DELETE SET NULL
);
CREATE INDEX email_block_time_idx ON email_block (banned_at);
CREATE INDEX email_block_banned_by_idx ON email_block (banned_by);
-- What site admins did, written in the transaction of the action itself: BAN (target_user_id the removed account,
-- target_id its email_block row, reason), UNBAN (target_id the removed email_block row), AVATAR_REMOVED (a moderation
-- removal or block of a profile picture: target_id the picture, target_user_id its account while known) and
-- CLUB_ADMIN_PROMOTED (a ban removed a club's last admin: target_id the club, target_user_id the promoted member).
-- Target IDs have no foreign key, so a row outlives what it names; a deleted admin leaves actor_id NULL.
CREATE TABLE admin_action (
    id UUID CONSTRAINT pk_admin_action PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id UUID,
    action VARCHAR(30) NOT NULL,
    target_user_id UUID,
    target_id UUID,
    reason VARCHAR(500),
    CONSTRAINT ck_admin_action_action CHECK (action IN ('BAN', 'UNBAN', 'AVATAR_REMOVED', 'CLUB_ADMIN_PROMOTED')),
    CONSTRAINT fk_admin_action_actor FOREIGN KEY (actor_id) REFERENCES app_user (id) ON DELETE SET NULL
);
CREATE INDEX admin_action_time_idx ON admin_action (created_at);
CREATE INDEX admin_action_actor_idx ON admin_action (actor_id);
-- When a membership began: a ban that removes a club's last admin promotes the longest-standing remaining member.
-- Memberships from before this column all get the migration time (their order then falls back to the account ID).
ALTER TABLE membership ADD COLUMN joined_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
