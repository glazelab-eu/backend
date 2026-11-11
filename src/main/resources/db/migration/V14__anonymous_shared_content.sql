-- Keep shared catalog content without retaining the deleted account or a replacement user identity.
-- Existing named foreign keys remain in place; new anonymous references are SQL NULL.
ALTER TABLE glaze ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE combination ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE image ALTER COLUMN uploaded_by DROP NOT NULL;
ALTER TABLE club ALTER COLUMN owner_id DROP NOT NULL;
