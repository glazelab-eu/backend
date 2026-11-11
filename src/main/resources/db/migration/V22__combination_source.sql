-- The combination a variation was recorded from; NULL for an original, or once the source was deleted.
-- Whether a viewer is told about the source is decided per request (a draft source stays private).
ALTER TABLE combination ADD COLUMN based_on_id UUID;
ALTER TABLE combination ADD CONSTRAINT fk_combination_based_on
    FOREIGN KEY (based_on_id) REFERENCES combination (id) ON DELETE SET NULL;
