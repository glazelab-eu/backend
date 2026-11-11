-- The size of the jar a stock entry is bought in, in grams (null while unknown); "+1 jar" adds it to the amount.
ALTER TABLE stock ADD COLUMN jar_size_g INTEGER;
ALTER TABLE stock ADD CONSTRAINT ck_stock_jar_size CHECK (jar_size_g IS NULL OR jar_size_g > 0);
