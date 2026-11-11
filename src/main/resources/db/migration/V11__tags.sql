-- Normalized tags (lower case words joined by hyphens, accents kept) on glazes and combinations.
-- The primary keys de-duplicate tags per item; the API normalizes and limits them.
CREATE TABLE glaze_tag (
    glaze_id UUID NOT NULL,
    tag VARCHAR(40) NOT NULL,
    CONSTRAINT pk_glaze_tag PRIMARY KEY (glaze_id, tag),
    CONSTRAINT fk_glaze_tag_glaze_id FOREIGN KEY (glaze_id) REFERENCES glaze(id),
    CONSTRAINT ck_glaze_tag_not_empty CHECK (tag <> '')
);
CREATE TABLE combination_tag (
    combination_id UUID NOT NULL,
    tag VARCHAR(40) NOT NULL,
    CONSTRAINT pk_combination_tag PRIMARY KEY (combination_id, tag),
    CONSTRAINT fk_combination_tag_combination_id FOREIGN KEY (combination_id) REFERENCES combination(id),
    CONSTRAINT ck_combination_tag_not_empty CHECK (tag <> '')
);
