-- Separate queue kind: the teacher broadcast assembler must never claim student recordings.
ALTER TABLE prepared_job DROP CHECK ck_prepared_job_kind;
ALTER TABLE prepared_job ADD CONSTRAINT ck_prepared_job_kind
    CHECK (kind IN ('QUESTIONS','TTS','ASSEMBLE','ANSWER_MEDIA','TRANSCRIBE','SCORE'));

CREATE TABLE prepared_answer_media (
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    declaration_revision BIGINT NOT NULL,
    deployment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    proof_json MEDIUMTEXT NULL,
    verified_at DATETIME(3) NULL,
    PRIMARY KEY (answer_id),
    UNIQUE KEY uk_prepared_answer_media_job (job_id),
    CONSTRAINT fk_prepared_answer_media_answer FOREIGN KEY (assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_answer_media_job FOREIGN KEY (assessment_id,student_id,job_id)
        REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT ck_prepared_answer_media_revision CHECK (declaration_revision >= 1),
    CONSTRAINT ck_prepared_answer_media_proof CHECK ((proof_json IS NULL AND verified_at IS NULL)
        OR (proof_json IS NOT NULL AND verified_at IS NOT NULL AND JSON_VALID(proof_json)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
