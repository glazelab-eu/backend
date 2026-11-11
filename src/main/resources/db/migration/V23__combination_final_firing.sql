-- The firing that produced a combination's result; every field is optional and existing rows have none.
-- Earlier or extra firings stay custom steps (label and details), so this is the only structured firing data.
-- The cone's shape (optional leading 0, then 1 to 2 digits without a leading zero) is checked by the API.
ALTER TABLE combination ADD COLUMN cone VARCHAR(3);
ALTER TABLE combination ADD COLUMN temperature_c INTEGER;
ALTER TABLE combination ADD COLUMN atmosphere VARCHAR(10);
ALTER TABLE combination ADD COLUMN clay_body VARCHAR(120);
ALTER TABLE combination ADD COLUMN kiln VARCHAR(80);
ALTER TABLE combination ADD COLUMN fired_on DATE;
ALTER TABLE combination ADD CONSTRAINT ck_combination_temperature
    CHECK (temperature_c IS NULL OR temperature_c BETWEEN 100 AND 1600);
ALTER TABLE combination ADD CONSTRAINT ck_combination_atmosphere
    CHECK (atmosphere IS NULL OR atmosphere IN ('OXIDATION', 'REDUCTION', 'OTHER'));
