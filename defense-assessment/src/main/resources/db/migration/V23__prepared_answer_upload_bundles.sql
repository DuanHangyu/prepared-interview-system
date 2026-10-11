-- Immutable transport grouping. Logical original chunks and whole-answer SAVED remain separate.
CREATE TABLE prepared_answer_upload_bundle (
    id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    command_json MEDIUMTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    command_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size INT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deployment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_bundle_scope (assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_bundle_answer FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT ck_prepared_bundle_bytes CHECK (byte_size BETWEEN 1 AND 8388608),
    CONSTRAINT ck_prepared_bundle_times CHECK (confirmed_at IS NULL OR confirmed_at>=reserved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE prepared_answer_chunk
    ADD COLUMN bundle_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN bundle_offset INT NULL,
    ADD CONSTRAINT fk_prepared_chunk_bundle FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id,bundle_id)
        REFERENCES prepared_answer_upload_bundle(assessment_id,student_id,attempt_id,answer_id,id),
    ADD CONSTRAINT ck_prepared_chunk_location CHECK (
        (bundle_id IS NULL AND bundle_offset IS NULL) OR
        (bundle_id IS NOT NULL AND bundle_offset IS NOT NULL AND bundle_offset>=0 AND bundle_offset+byte_size<=8388608)
    );
-- When bundle_id is present, the physical locator is the bundle object + offset + byte_size.
-- V22 object_key remains the immutable SINGLE-mode fallback, never evidence that an object exists.
