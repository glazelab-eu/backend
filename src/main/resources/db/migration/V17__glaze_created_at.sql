-- When a glaze was added to the library, for Explore's "New" view (the last 14 days). There is no
-- deployment yet (user, 2026-10-28), so existing glazes get the migration's time through the
-- default; the application sets it for new glazes.
ALTER TABLE glaze ADD COLUMN created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL;
