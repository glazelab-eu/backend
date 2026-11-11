-- The activity feed (backend guide "Activity feed"): derived at read time from the rows that describe each addition,
-- so it stores no events. Two things were missing.
-- 1. When a stock entry was created (a glaze new on a buddy's or a club's shelf). NULL for every existing entry on
--    purpose: nothing records when it was added, so no date is invented and an old entry never shows as new.
ALTER TABLE stock ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX stock_user_created_idx ON stock (user_id, created_at);
CREATE INDEX stock_club_created_idx ON stock (club_id, created_at);
-- Buddies' and co-members' publications, newest first.
CREATE INDEX combination_author_published_idx ON combination (created_by, published_at);
-- The viewer's memberships by account (the feed's co-member and shared-club lookups; the primary key leads with
-- club_id, so without it every feed read scans all memberships).
CREATE INDEX membership_user_idx ON membership (user_id);
-- 2. Per account: the newest entry it has seen (server side, so every device agrees; NULL = nothing seen yet, every
--    entry of the window counts as new) and its two sources, on by default.
ALTER TABLE app_user ADD COLUMN feed_seen_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN feed_buddies BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE app_user ADD COLUMN feed_clubs BOOLEAN DEFAULT TRUE NOT NULL;
