-- Reserved immutable bytes are distinct from actual object verification/receipts and whole-answer SAVED.
CREATE TABLE prepared_answer_segment (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    mime_type VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_segment_order (answer_id,ordinal),
    UNIQUE KEY uk_prepared_segment_scope (assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_segment_answer FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT ck_prepared_segment_order CHECK (ordinal BETWEEN 1 AND 1000),
    CONSTRAINT ck_prepared_segment_mime CHECK (mime_type IN ('audio/webm;codecs=opus','audio/webm','audio/ogg;codecs=opus','audio/ogg','audio/mp4'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_answer_chunk (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    segment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    chunk_index INT NOT NULL,
    byte_size INT NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    deployment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_at DATETIME(3) NOT NULL,
    confirmed_at DATETIME(3) NULL,
    PRIMARY KEY (segment_id,chunk_index),
    KEY ix_prepared_chunk_answer (assessment_id,student_id,attempt_id,answer_id),
    CONSTRAINT fk_prepared_chunk_segment FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id,segment_id)
        REFERENCES prepared_answer_segment(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT ck_prepared_chunk_bounds CHECK (chunk_index BETWEEN 0 AND 9999 AND byte_size BETWEEN 1 AND 2097152),
    CONSTRAINT ck_prepared_chunk_times CHECK (confirmed_at IS NULL OR confirmed_at>=reserved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
