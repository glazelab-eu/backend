ALTER TABLE club ADD COLUMN house_rules VARCHAR(10000) NOT NULL DEFAULT '';
ALTER TABLE club ADD COLUMN invites_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE club ADD COLUMN invite_hash VARCHAR(64);
CREATE UNIQUE INDEX club_invite_hash_unique ON club(invite_hash);
ALTER TABLE membership ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE membership ADD CONSTRAINT membership_role_check CHECK (role IN ('MEMBER', 'ADMIN'));
UPDATE membership SET role = 'ADMIN'
WHERE EXISTS (SELECT 1 FROM club WHERE club.id = membership.club_id AND club.owner_id = membership.user_id);
