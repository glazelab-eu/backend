CREATE TABLE image_deletion (
    id UUID CONSTRAINT pk_image_deletion PRIMARY KEY,
    image_id UUID NOT NULL,
    glaze_id UUID,
    combination_id UUID,
    title TEXT NOT NULL,
    visibility VARCHAR(16) NOT NULL,
    uploader_id UUID,
    deleted_by UUID,
    deleted_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_image_deletion_target CHECK ((glaze_id IS NULL) <> (combination_id IS NULL)),
    CONSTRAINT ck_image_deletion_visibility CHECK (visibility IN ('PUBLIC', 'CLUB', 'PRIVATE'))
);
CREATE INDEX image_deletion_time_idx ON image_deletion (deleted_at);
