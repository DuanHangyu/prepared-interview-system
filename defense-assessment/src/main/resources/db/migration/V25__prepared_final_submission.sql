-- Final receipt and processing outbox share a transaction. No media/model IO here.
CREATE TABLE prepared_submission (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submitted_revision BIGINT NOT NULL,
    answer_count INT NOT NULL,
    submitted_at DATETIME(3) NOT NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_submission_attempt (attempt_id),
    UNIQUE KEY uk_prepared_submission_scope (assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_submission_attempt FOREIGN KEY (assessment_id,student_id,attempt_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id),
    CONSTRAINT fk_prepared_submission_paper FOREIGN KEY (assessment_id,paper_version_id)
        REFERENCES prepared_paper_version(assessment_id,id),
    CONSTRAINT ck_prepared_submission_revision CHECK (submitted_revision >= 1),
    CONSTRAINT ck_prepared_submission_count CHECK (answer_count BETWEEN 1 AND 50),
    CONSTRAINT ck_prepared_submission_snapshot CHECK (JSON_VALID(snapshot_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_submission_answer (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    transcribe_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    score_job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (answer_id),
    UNIQUE KEY uk_prepared_submission_answer_order (submission_id,ordinal),
    UNIQUE KEY uk_prepared_submission_transcribe (transcribe_job_id),
    UNIQUE KEY uk_prepared_submission_score (score_job_id),
    CONSTRAINT fk_prepared_submission_answer_head FOREIGN KEY (assessment_id,student_id,attempt_id,submission_id)
        REFERENCES prepared_submission(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_submission_answer_original FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_submission_transcribe FOREIGN KEY (assessment_id,student_id,transcribe_job_id)
        REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT fk_prepared_submission_score FOREIGN KEY (assessment_id,student_id,score_job_id)
        REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT ck_prepared_submission_answer_order CHECK (ordinal BETWEEN 1 AND 50)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_submission_command (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    submission_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (assessment_id,student_id,attempt_id,idempotency_key),
    CONSTRAINT fk_prepared_submission_command_head FOREIGN KEY (assessment_id,student_id,attempt_id,submission_id)
        REFERENCES prepared_submission(assessment_id,student_id,attempt_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
