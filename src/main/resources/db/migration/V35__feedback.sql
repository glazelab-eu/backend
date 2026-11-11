-- In-app feedback (FEEDBACK-1, backend guide "Feedback"): a signed-in account sends a short text, optionally with the
-- context the app attached (route name, build label, language, device class; the sender can leave it out) and up to
-- three photos. Only site admins read it. It goes with the account (ON DELETE CASCADE; the photos' objects are deleted
-- by the account deletion after commit) and 90 days after a site admin marked it done (glazelab.feedback.retention,
-- promised by the terms).
CREATE TABLE feedback (
    id UUID CONSTRAINT pk_feedback PRIMARY KEY,
    user_id UUID NOT NULL,
    message VARCHAR(2000) NOT NULL,
    route_name VARCHAR(40),
    build_label VARCHAR(64),
    language VARCHAR(35),
    device VARCHAR(10),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(10) NOT NULL,
    done_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_feedback_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT ck_feedback_done CHECK ((status = 'OPEN' AND done_at IS NULL) OR (status = 'DONE' AND done_at IS NOT NULL)),
    CONSTRAINT ck_feedback_device CHECK (device IN ('PHONE', 'TABLET', 'DESKTOP')),
    CONSTRAINT fk_feedback_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
-- The site admins' listing (newest first, paged by time and ID), the account deletion and the retention purge.
CREATE INDEX feedback_time_idx ON feedback (created_at, id);
CREATE INDEX feedback_user_idx ON feedback (user_id);
CREATE INDEX feedback_done_idx ON feedback (done_at);
-- A photo of a feedback: two stored objects (storage_key and its card copy, ImageStorage.cardKey), re-encoded without
-- metadata like every photo; photo_index orders them (0 to 2).
CREATE TABLE feedback_photo (
    id UUID CONSTRAINT pk_feedback_photo PRIMARY KEY,
    feedback_id UUID NOT NULL,
    photo_index INTEGER NOT NULL,
    storage_key VARCHAR(64) NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    CONSTRAINT uq_feedback_photo_key UNIQUE (storage_key),
    CONSTRAINT uq_feedback_photo_index UNIQUE (feedback_id, photo_index),
    CONSTRAINT ck_feedback_photo_index CHECK (photo_index BETWEEN 0 AND 2),
    CONSTRAINT fk_feedback_photo_feedback FOREIGN KEY (feedback_id) REFERENCES feedback (id) ON DELETE CASCADE
);
