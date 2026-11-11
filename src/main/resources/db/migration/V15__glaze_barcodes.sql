-- Retail barcodes (EAN-13, UPC-A stored as EAN-13, EAN-8) of glaze packaging; see model/Barcodes.
-- The primary key makes a barcode unique in the whole library; a glaze may have several (jar sizes).
CREATE TABLE glaze_barcode (
    barcode VARCHAR(13) NOT NULL,
    glaze_id UUID NOT NULL,
    CONSTRAINT pk_glaze_barcode PRIMARY KEY (barcode),
    CONSTRAINT fk_glaze_barcode_glaze_id FOREIGN KEY (glaze_id) REFERENCES glaze(id),
    CONSTRAINT ck_glaze_barcode_length CHECK (CHAR_LENGTH(barcode) = 13 OR CHAR_LENGTH(barcode) = 8)
);
CREATE INDEX glaze_barcode_glaze_idx ON glaze_barcode (glaze_id);
