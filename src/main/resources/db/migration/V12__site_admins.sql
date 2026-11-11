-- Site admins may remove glazes. There is no endpoint that sets this flag; grant it with SQL only
-- (see README, "Site admins"), so no account can make itself an admin.
ALTER TABLE app_user ADD COLUMN site_admin BOOLEAN DEFAULT FALSE NOT NULL;
