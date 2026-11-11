-- Glaze buddies (service.BuddyService): two accounts that see which glazes the other has on their personal shelf.
-- buddy_link: at most one active link per account; the row keeps only the SHA-256 hash of the random token the
-- owner shares, and replacing the link overwrites the hash, so an old link stops working at once.
CREATE TABLE buddy_link (
    user_id UUID CONSTRAINT pk_buddy_link PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_buddy_link_token UNIQUE (token_hash),
    CONSTRAINT fk_buddy_link_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
-- buddy: one row per pair of accounts, the smaller ID (byte order) first, so a pair is stored once whoever asked.
-- PENDING until the account that did not ask accepts; declining, cancelling and removing delete the row.
CREATE TABLE buddy (
    user_a UUID NOT NULL,
    user_b UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    requested_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_buddy PRIMARY KEY (user_a, user_b),
    CONSTRAINT ck_buddy_order CHECK (user_a < user_b),
    CONSTRAINT ck_buddy_status CHECK (status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT ck_buddy_requested_by CHECK (requested_by = user_a OR requested_by = user_b),
    CONSTRAINT ck_buddy_accepted_at CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL)),
    CONSTRAINT fk_buddy_user_a FOREIGN KEY (user_a) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_buddy_user_b FOREIGN KEY (user_b) REFERENCES app_user (id) ON DELETE CASCADE
);
CREATE INDEX buddy_user_b_idx ON buddy (user_b);
-- A personal stock entry its owner keeps from their buddies; club stock is never shown to buddies and never has it.
ALTER TABLE stock ADD COLUMN keep_private BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE stock ADD CONSTRAINT ck_stock_keep_private CHECK (keep_private = FALSE OR user_id IS NOT NULL);
