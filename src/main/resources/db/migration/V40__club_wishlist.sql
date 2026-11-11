-- A club's wishlist (backend guide "Authorization and visibility", "Club wishlist"): glazes from the catalogue and other
-- equipment the members would like the club to get. Any member adds an entry; its adder or a club admin removes it or
-- marks it acquired. The entries belong to the club (ON DELETE CASCADE) and go with a glaze removed from the catalogue;
-- added_by records the member who added one: information and their rights only, cleared when they leave the club and
-- nulled with their account (ON DELETE SET NULL), so the wishlist outlives its adders.
CREATE TABLE club_wishlist (
    id UUID CONSTRAINT pk_club_wishlist PRIMARY KEY,
    club_id UUID NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    glaze_id UUID,
    item_name VARCHAR(120),
    link VARCHAR(2000),
    note VARCHAR(200),
    added_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    acquired_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_club_wishlist_type CHECK (entry_type IN ('GLAZE', 'ITEM')),
    -- A glaze entry names a catalogue glaze and nothing else; an item has a name and a link and no glaze.
    CONSTRAINT ck_club_wishlist_fields CHECK (
        (entry_type = 'GLAZE' AND glaze_id IS NOT NULL AND item_name IS NULL AND link IS NULL)
        OR (entry_type = 'ITEM' AND glaze_id IS NULL AND item_name IS NOT NULL AND link IS NOT NULL)),
    CONSTRAINT fk_club_wishlist_club FOREIGN KEY (club_id) REFERENCES club (id) ON DELETE CASCADE,
    CONSTRAINT fk_club_wishlist_glaze FOREIGN KEY (glaze_id) REFERENCES glaze (id) ON DELETE CASCADE,
    CONSTRAINT fk_club_wishlist_added_by FOREIGN KEY (added_by) REFERENCES app_user (id) ON DELETE SET NULL
);
-- The club's listing and its open count; the glaze removal's cascade; the adder's leave and account deletion.
CREATE INDEX club_wishlist_club_idx ON club_wishlist (club_id, created_at);
CREATE INDEX club_wishlist_glaze_idx ON club_wishlist (glaze_id);
CREATE INDEX club_wishlist_added_by_idx ON club_wishlist (added_by);
