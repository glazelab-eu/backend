-- A photo's optional caption and the date it was fired; existing photos have neither. The date range
-- matches the final firing's (1900 to 2100) so the API refuses a year PostgreSQL cannot store with a 400.
ALTER TABLE image ADD COLUMN caption VARCHAR(200);
ALTER TABLE image ADD COLUMN fired_on DATE;
ALTER TABLE image ADD CONSTRAINT ck_image_fired_on
    CHECK (fired_on IS NULL OR fired_on BETWEEN DATE '1900-01-01' AND DATE '2100-12-31');
