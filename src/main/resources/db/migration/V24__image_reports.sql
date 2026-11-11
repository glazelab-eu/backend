-- A signed-in user's report of a photo they can see, for the site admins' queue. Deleting the photo resolves
-- its reports (the deletion log keeps the evidence); a deleted reporter leaves the report without a name.
CREATE TABLE image_report (
    id UUID CONSTRAINT pk_image_report PRIMARY KEY,
    image_id UUID NOT NULL,
    reporter_id UUID,
    reason VARCHAR(500),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_image_report_image FOREIGN KEY (image_id) REFERENCES image (id) ON DELETE CASCADE,
    CONSTRAINT fk_image_report_reporter FOREIGN KEY (reporter_id) REFERENCES app_user (id) ON DELETE SET NULL,
    CONSTRAINT uq_image_report_reporter UNIQUE (image_id, reporter_id)
);
CREATE INDEX image_report_time_idx ON image_report (created_at);
CREATE INDEX image_report_reporter_idx ON image_report (reporter_id);
