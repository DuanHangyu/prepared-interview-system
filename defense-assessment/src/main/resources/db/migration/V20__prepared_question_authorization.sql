-- Per-question authorization only: no overall/idle deadline or heartbeat-based writer takeover.
ALTER TABLE prepared_attempt
    ADD UNIQUE KEY uk_prepared_attempt_answer_scope (assessment_id, student_id, id, paper_version_id);
ALTER TABLE prepared_question_snapshot
    ADD UNIQUE KEY uk_prepared_question_answer_scope (assessment_id, paper_version_id, question_id, ordinal, answer_seconds);

CREATE TABLE prepared_answer (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    capture_client_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_seconds INT NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'RECORDING',
    revision BIGINT NOT NULL DEFAULT 0,
    started_at DATETIME(3) NOT NULL,
    answer_deadline DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_answer_question (attempt_id, question_id),
    UNIQUE KEY uk_prepared_answer_order (attempt_id, ordinal),
    UNIQUE KEY uk_prepared_answer_receipt_scope (assessment_id, student_id, attempt_id, id),
    CONSTRAINT fk_prepared_answer_attempt FOREIGN KEY (assessment_id, student_id, attempt_id, paper_version_id)
        REFERENCES prepared_attempt(assessment_id, student_id, id, paper_version_id),
    CONSTRAINT fk_prepared_answer_question FOREIGN KEY (assessment_id, paper_version_id, question_id, ordinal, answer_seconds)
        REFERENCES prepared_question_snapshot(assessment_id, paper_version_id, question_id, ordinal, answer_seconds),
    CONSTRAINT ck_prepared_answer_state CHECK (state IN ('RECORDING','UPLOAD_PENDING','SAVED','REQUIRES_REVIEW')),
    CONSTRAINT ck_prepared_answer_time CHECK (answer_seconds BETWEEN 1 AND 1800
        AND answer_deadline = TIMESTAMPADD(SECOND, answer_seconds, started_at)),
    CONSTRAINT ck_prepared_answer_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_question_start_receipt (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (attempt_id, idempotency_key),
    CONSTRAINT fk_prepared_question_receipt_answer FOREIGN KEY (assessment_id, student_id, attempt_id, answer_id)
        REFERENCES prepared_answer(assessment_id, student_id, attempt_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
