-- A photo's focus point: where the cards' covers centre their crop, in percent of the width and height.
-- Both set or both null (null: the centre, as for every photo before).
ALTER TABLE image ADD COLUMN focus_x INTEGER;
ALTER TABLE image ADD COLUMN focus_y INTEGER;
ALTER TABLE image ADD CONSTRAINT ck_image_focus CHECK (
    (focus_x IS NULL AND focus_y IS NULL)
    OR (focus_x IS NOT NULL AND focus_y IS NOT NULL AND focus_x BETWEEN 0 AND 100 AND focus_y BETWEEN 0 AND 100));
