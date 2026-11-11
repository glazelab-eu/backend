-- Existing code-less glazes keep their UUID and relationships. New submissions require a code.
ALTER TABLE glaze ADD COLUMN catalog_key VARCHAR(161);
UPDATE glaze SET code = UPPER(TRIM(code)) WHERE code IS NOT NULL;
UPDATE glaze SET catalog_key = LOWER(TRIM(brand)) || ':' || code
    WHERE code IS NOT NULL AND code <> '';
-- Deliberately fail on legacy duplicates instead of deleting or merging user data.
CREATE UNIQUE INDEX glaze_catalog_key_unique ON glaze(catalog_key);
