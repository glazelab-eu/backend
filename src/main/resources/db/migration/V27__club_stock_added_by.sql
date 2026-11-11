-- Club stock belongs to its club; added_by records which member put the entry on the club's shelf. It is
-- information and the removal right of that member only: leaving the club clears it, and a deleted account
-- leaves it empty. Personal stock (user_id set) never has one.
ALTER TABLE stock ADD COLUMN added_by UUID;
ALTER TABLE stock ADD CONSTRAINT fk_stock_added_by FOREIGN KEY (added_by) REFERENCES app_user (id) ON DELETE SET NULL;
ALTER TABLE stock ADD CONSTRAINT ck_stock_added_by CHECK (added_by IS NULL OR club_id IS NOT NULL);
CREATE INDEX stock_added_by_idx ON stock (added_by);
