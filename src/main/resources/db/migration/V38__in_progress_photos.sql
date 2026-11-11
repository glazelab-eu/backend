-- A fourth photo kind, a piece in progress (glazed but unfired, bisque with slip): combination photos only.
ALTER TABLE image DROP CONSTRAINT ck_image_kind;
ALTER TABLE image ADD CONSTRAINT ck_image_kind CHECK (kind IN ('TEST_TILE','FINISHED','PACKAGING','IN_PROGRESS'));
ALTER TABLE image ADD CONSTRAINT ck_image_in_progress_target CHECK (kind <> 'IN_PROGRESS' OR combination_id IS NOT NULL);
