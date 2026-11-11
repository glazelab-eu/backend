-- The public change log of glaze corrections: one row per changed field (name, notes or code) with the old
-- and new value. It goes with its glaze; a deleted editor leaves the row without a name.
CREATE TABLE glaze_edit (
    id UUID CONSTRAINT pk_glaze_edit PRIMARY KEY,
    glaze_id UUID NOT NULL,
    editor_id UUID,
    edited_at TIMESTAMP WITH TIME ZONE NOT NULL,
    field VARCHAR(10) NOT NULL,
    old_value VARCHAR(1000),
    new_value VARCHAR(1000),
    CONSTRAINT fk_glaze_edit_glaze FOREIGN KEY (glaze_id) REFERENCES glaze (id) ON DELETE CASCADE,
    CONSTRAINT fk_glaze_edit_editor FOREIGN KEY (editor_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT ck_glaze_edit_field CHECK (field IN ('NAME', 'NOTES', 'CODE')),
    -- Only notes may be empty (null); a name and a code always have a value.
    CONSTRAINT ck_glaze_edit_values CHECK (field = 'NOTES' OR (old_value IS NOT NULL AND new_value IS NOT NULL))
);
CREATE INDEX glaze_edit_glaze_time_idx ON glaze_edit (glaze_id, edited_at);
CREATE INDEX glaze_edit_editor_idx ON glaze_edit (editor_id);
