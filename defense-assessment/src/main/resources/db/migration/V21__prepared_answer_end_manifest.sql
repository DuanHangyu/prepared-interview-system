-- Immutable client declarations are NOT verified objects, SAVED answers or final submission receipts.
CREATE TABLE prepared_answer_end (
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_json JSON NOT NULL,
    declaration_state VARCHAR(24) NOT NULL,
    review_reasons JSON NOT NULL,
    byte_size BIGINT NOT NULL,
    answer_revision BIGINT NOT NULL,
    effective_ended_at DATETIME(3) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    PRIMARY KEY (answer_id),
    UNIQUE KEY uk_prepared_end_scope (assessment_id, student_id, attempt_id, answer_id),
    CONSTRAINT fk_prepared_end_answer FOREIGN KEY (assessment_id, student_id, attempt_id, answer_id)
        REFERENCES prepared_answer(assessment_id, student_id, attempt_id, id),
    CONSTRAINT ck_prepared_end_state CHECK (declaration_state IN ('UPLOAD_PENDING','REQUIRES_REVIEW')),
    CONSTRAINT ck_prepared_end_size CHECK (byte_size BETWEEN 0 AND 67108864 AND answer_revision >= 1),
    CONSTRAINT ck_prepared_end_time CHECK (effective_ended_at <= accepted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_answer_end_receipt (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (attempt_id, idempotency_key),
    CONSTRAINT fk_prepared_end_receipt_manifest FOREIGN KEY (assessment_id, student_id, attempt_id, answer_id)
        REFERENCES prepared_answer_end(assessment_id, student_id, attempt_id, answer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
