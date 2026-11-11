-- Images belong to exactly one target: a combination or a single glaze.
ALTER TABLE image ADD COLUMN glaze_id UUID;
ALTER TABLE image ALTER COLUMN combination_id DROP NOT NULL;
ALTER TABLE image ADD CONSTRAINT fk_image_glaze_id FOREIGN KEY (glaze_id) REFERENCES glaze(id);
ALTER TABLE image ADD CONSTRAINT ck_image_single_target
    CHECK ((combination_id IS NULL) <> (glaze_id IS NULL));
CREATE INDEX image_glaze_idx ON image(glaze_id);
