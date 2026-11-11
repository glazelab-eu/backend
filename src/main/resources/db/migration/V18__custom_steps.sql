-- A combination is an ordered list of steps: a glaze application (GLAZE, as every existing row) or a
-- custom step such as a firing (CUSTOM: label and optional details in notes, no glaze, coats or method).
ALTER TABLE combination_step ADD COLUMN step_type VARCHAR(10) DEFAULT 'GLAZE' NOT NULL;
ALTER TABLE combination_step ALTER COLUMN step_type DROP DEFAULT;
ALTER TABLE combination_step ADD COLUMN label VARCHAR(80);
ALTER TABLE combination_step ALTER COLUMN glaze_id DROP NOT NULL;
ALTER TABLE combination_step ALTER COLUMN coats DROP NOT NULL;
ALTER TABLE combination_step ADD CONSTRAINT ck_step_type CHECK (step_type IN ('GLAZE', 'CUSTOM'));
ALTER TABLE combination_step ADD CONSTRAINT ck_step_shape CHECK (
    (step_type = 'GLAZE' AND glaze_id IS NOT NULL AND coats IS NOT NULL AND label IS NULL)
    OR (step_type = 'CUSTOM' AND label IS NOT NULL AND glaze_id IS NULL AND coats IS NULL AND method IS NULL));
