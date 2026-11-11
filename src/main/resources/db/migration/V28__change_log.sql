-- The change log behind the incremental sync (GET /api/sync): one row per changed glaze or combination and
-- audience, written last in the changing transaction by service.ChangeRecorder. The revision is the cursor
-- (an identity, so it grows with every insert); changed_at bounds the overlap window and the 30-day retention.
-- The audience says who may receive the row: everyone (PUBLIC), the members of one club (CLUB, audience_id =
-- club) or one account (USER, audience_id = account). Rows hold IDs only, never content, and no foreign keys:
-- a row must outlive its item so a removal reaches clients as a tombstone.
CREATE TABLE change_log (
    revision BIGINT GENERATED ALWAYS AS IDENTITY CONSTRAINT pk_change_log PRIMARY KEY,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    kind VARCHAR(12) NOT NULL,
    item_id UUID NOT NULL,
    audience VARCHAR(8) NOT NULL,
    audience_id UUID,
    CONSTRAINT ck_change_log_kind CHECK (kind IN ('GLAZE', 'COMBINATION')),
    CONSTRAINT ck_change_log_audience CHECK (audience IN ('PUBLIC', 'CLUB', 'USER')),
    -- PUBLIC needs no ID; a club or an account always names one.
    CONSTRAINT ck_change_log_audience_id CHECK ((audience = 'PUBLIC') = (audience_id IS NULL))
);
CREATE INDEX change_log_changed_at_idx ON change_log (changed_at);
