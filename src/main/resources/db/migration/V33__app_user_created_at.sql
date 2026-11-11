-- The registration time of an account, shown to site admins in the account list (backend guide "Admin user list").
-- Set for accounts registered from now on; NULL for every existing account on purpose: nothing reliable says when
-- they registered (the terms acceptance time and tokens are not registration times), so no date is invented.
ALTER TABLE app_user ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
