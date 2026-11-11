-- When a combination was published; NULL while it is a draft, which only its author can see or open.
-- Every existing combination counts as published at its creation time.
ALTER TABLE combination ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
UPDATE combination SET published_at = created_at;
