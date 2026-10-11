-- Immutable model result versions are separate from reliable submission and the originals.
CREATE TABLE prepared_processing_result (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_json MEDIUMTEXT NOT NULL,
    provider VARCHAR(96) NOT NULL,
    model VARCHAR(96) NOT NULL,
    config_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_result_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    completed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_result_job(job_id),
    UNIQUE KEY uk_prepared_result_version(answer_id,kind,version_no),
    UNIQUE KEY uk_prepared_result_scope(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_result_answer FOREIGN KEY(answer_id) REFERENCES prepared_submission_answer(answer_id),
    CONSTRAINT fk_prepared_result_original FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_result_job FOREIGN KEY(assessment_id,student_id,job_id) REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT fk_prepared_result_source FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,source_result_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT ck_prepared_result_kind CHECK(kind IN ('TRANSCRIBE','SCORE')),
    CONSTRAINT ck_prepared_result_version CHECK(version_no BETWEEN 1 AND 100000),
    CONSTRAINT ck_prepared_result_source CHECK((kind='TRANSCRIBE' AND source_result_id IS NULL) OR (kind='SCORE' AND source_result_id IS NOT NULL)),
    CONSTRAINT ck_prepared_result_json CHECK(JSON_VALID(content_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_processing_head (
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    transcript_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    score_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    confirmed_by INT NULL,
    confirmed_at DATETIME(3) NULL,
    PRIMARY KEY(answer_id),
    CONSTRAINT fk_prepared_processing_head_answer FOREIGN KEY(answer_id) REFERENCES prepared_submission_answer(answer_id),
    CONSTRAINT fk_prepared_processing_head_original FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_head_transcript FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,transcript_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_head_score FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,score_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_head_confirmer FOREIGN KEY(confirmed_by) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_head_revision CHECK(revision>=0),
    CONSTRAINT ck_prepared_head_confirmation CHECK((confirmed_by IS NULL AND confirmed_at IS NULL) OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND score_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_score_request (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transcript_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    requested_revision BIGINT NOT NULL,
    requested_by INT NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    PRIMARY KEY(job_id),
    CONSTRAINT fk_prepared_score_request_job FOREIGN KEY(assessment_id,student_id,job_id) REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT fk_prepared_score_request_answer FOREIGN KEY(answer_id) REFERENCES prepared_submission_answer(answer_id),
    CONSTRAINT fk_prepared_score_request_original FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id) REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_score_request_source FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,transcript_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_score_request_actor FOREIGN KEY(requested_by) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_score_request_revision CHECK(requested_revision>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_result_command (
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_json TEXT NOT NULL,
    PRIMARY KEY(assessment_id,actor_id,answer_id,action,idempotency_key),
    CONSTRAINT fk_prepared_result_command_answer FOREIGN KEY(answer_id) REFERENCES prepared_submission_answer(answer_id),
    CONSTRAINT fk_prepared_result_command_actor FOREIGN KEY(actor_id) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_result_command_action CHECK(action IN ('REGRADE','CONFIRM')),
    CONSTRAINT ck_prepared_result_command_json CHECK(JSON_VALID(receipt_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
