-- A site admin may correct the title of another account's combination; the admin log keeps both titles, like glaze_edit.
ALTER TABLE admin_action ADD COLUMN old_value VARCHAR(160);
ALTER TABLE admin_action ADD COLUMN new_value VARCHAR(160);
ALTER TABLE admin_action DROP CONSTRAINT ck_admin_action_action;
ALTER TABLE admin_action ADD CONSTRAINT ck_admin_action_action
    CHECK (action IN ('BAN', 'UNBAN', 'AVATAR_REMOVED', 'CLUB_ADMIN_PROMOTED', 'TITLE_CORRECTED'));
-- A correction always carries both titles; no other action carries any.
ALTER TABLE admin_action ADD CONSTRAINT ck_admin_action_values CHECK (
    (action = 'TITLE_CORRECTED' AND old_value IS NOT NULL AND new_value IS NOT NULL)
    OR (action <> 'TITLE_CORRECTED' AND old_value IS NULL AND new_value IS NULL));
