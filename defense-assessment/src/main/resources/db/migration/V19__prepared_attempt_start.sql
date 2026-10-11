-- Durable prepared starts only. There is deliberately NO overall answer deadline.
-- Keep the existing assessment/student record uniqueness and all historical records unchanged.
ALTER TABLE student_assessment_record
    ADD UNIQUE KEY uk_prepared_record_scope (id, assessment_id, student_id);

CREATE TABLE prepared_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    record_id INT NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_no INT NOT NULL,
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'IN_PROGRESS',
    revision BIGINT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NOT NULL,
    answering_ended_at DATETIME(3) NULL,
    supplemental_upload_deadline DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_attempt_number (record_id, attempt_no),
    UNIQUE KEY uk_prepared_attempt_owner (assessment_id, student_id, id),
    UNIQUE KEY uk_prepared_attempt_anchor_scope (assessment_id, student_id, record_id, id),
    CONSTRAINT fk_prepared_attempt_record FOREIGN KEY (record_id, assessment_id, student_id)
        REFERENCES student_assessment_record(id, assessment_id, student_id),
    CONSTRAINT fk_prepared_attempt_paper FOREIGN KEY (assessment_id, paper_version_id)
        REFERENCES prepared_paper_version(assessment_id, id),
    CONSTRAINT ck_prepared_attempt_number CHECK (attempt_no >= 1 AND revision >= 0),
    CONSTRAINT ck_prepared_attempt_state CHECK (state IN ('IN_PROGRESS','UPLOAD_PENDING','SUBMITTED','REQUIRES_REVIEW','CANCELLED')),
    CONSTRAINT ck_prepared_attempt_upload_time CHECK (
        (answering_ended_at IS NULL AND supplemental_upload_deadline IS NULL) OR
        (answering_ended_at IS NOT NULL AND supplemental_upload_deadline IS NOT NULL
         AND answering_ended_at >= started_at AND supplemental_upload_deadline > answering_ended_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_attempt_anchor (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    record_id INT NOT NULL,
    active_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (assessment_id, student_id),
    UNIQUE KEY uk_prepared_active_record (record_id),
    CONSTRAINT fk_prepared_active_attempt FOREIGN KEY (assessment_id, student_id, record_id, active_attempt_id)
        REFERENCES prepared_attempt(assessment_id, student_id, record_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_attempt_start_receipt (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (assessment_id, student_id, idempotency_key),
    CONSTRAINT fk_prepared_start_receipt_attempt FOREIGN KEY (assessment_id, student_id, attempt_id)
        REFERENCES prepared_attempt(assessment_id, student_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
