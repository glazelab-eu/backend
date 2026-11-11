CREATE TABLE favorite (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    glaze_id VARCHAR(36) REFERENCES glaze(id),
    combination_id VARCHAR(36) REFERENCES combination(id),
    CHECK ((glaze_id IS NULL) <> (combination_id IS NULL)),
    UNIQUE (user_id, glaze_id),
    UNIQUE (user_id, combination_id)
);
