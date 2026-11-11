CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL
);
CREATE TABLE club (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    owner_id VARCHAR(36) NOT NULL REFERENCES app_user(id)
);
CREATE TABLE membership (
    club_id VARCHAR(36) NOT NULL REFERENCES club(id),
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    PRIMARY KEY (club_id, user_id)
);
CREATE TABLE glaze (
    id VARCHAR(36) PRIMARY KEY,
    brand VARCHAR(100) NOT NULL,
    name VARCHAR(120) NOT NULL,
    code VARCHAR(60),
    notes VARCHAR(1000),
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id)
);
CREATE TABLE stock (
    id VARCHAR(36) PRIMARY KEY,
    glaze_id VARCHAR(36) NOT NULL REFERENCES glaze(id),
    user_id VARCHAR(36) REFERENCES app_user(id),
    club_id VARCHAR(36) REFERENCES club(id),
    quantity_g INTEGER NOT NULL,
    CHECK (quantity_g >= 0 AND ((user_id IS NULL) <> (club_id IS NULL)))
);
CREATE UNIQUE INDEX stock_user_unique ON stock(glaze_id, user_id);
CREATE UNIQUE INDEX stock_club_unique ON stock(glaze_id, club_id);
CREATE TABLE combination (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    created_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP NOT NULL
);
CREATE TABLE combination_step (
    id VARCHAR(36) PRIMARY KEY,
    combination_id VARCHAR(36) NOT NULL REFERENCES combination(id),
    position INTEGER NOT NULL,
    glaze_id VARCHAR(36) NOT NULL REFERENCES glaze(id),
    coats INTEGER NOT NULL CHECK (coats > 0),
    method VARCHAR(80),
    notes VARCHAR(500),
    UNIQUE (combination_id, position)
);
CREATE INDEX step_glaze_idx ON combination_step(glaze_id);
CREATE TABLE image (
    id VARCHAR(36) PRIMARY KEY,
    combination_id VARCHAR(36) NOT NULL REFERENCES combination(id),
    uploaded_by VARCHAR(36) NOT NULL REFERENCES app_user(id),
    club_id VARCHAR(36) REFERENCES club(id),
    visibility VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    storage_key VARCHAR(120) NOT NULL UNIQUE,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CHECK (visibility IN ('PUBLIC','CLUB','PRIVATE') AND kind IN ('TEST_TILE','FINISHED')
        AND ((visibility = 'CLUB' AND club_id IS NOT NULL)
        OR (visibility <> 'CLUB' AND club_id IS NULL)))
);
CREATE INDEX image_combo_idx ON image(combination_id);
