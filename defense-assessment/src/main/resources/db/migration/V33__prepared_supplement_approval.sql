-- A time-limited approval for immutable original bytes. No recording/deadline/manifest is replaced.
ALTER TABLE prepared_attempt
    ADD COLUMN requires_supplement_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT ck_prepared_supplement_review CHECK(requires_supplement_review IN (0,1)
        AND (requires_supplement_review=0 OR (state='REQUIRES_REVIEW' AND answering_ended_at IS NOT NULL)));

CREATE TABLE prepared_supplement_grant (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id INT NOT NULL,
    approval_revision BIGINT NOT NULL,
    attempt_revision BIGINT NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    declaration_revision BIGINT NOT NULL,
    answer_deadline DATETIME(3) NOT NULL,
    answering_ended_at DATETIME(3) NOT NULL,
    automatic_deadline DATETIME(3) NOT NULL,
    approved_at DATETIME(3) NOT NULL,
    upload_deadline DATETIME(3) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_json MEDIUMTEXT NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_supplement_version(assessment_id,student_id,attempt_id,answer_id,approval_revision),
    UNIQUE KEY uk_prepared_supplement_command(assessment_id,actor_id,idempotency_key),
    CONSTRAINT fk_prepared_supplement_end FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer_end(assessment_id,student_id,attempt_id,answer_id),
    CONSTRAINT fk_prepared_supplement_paper FOREIGN KEY(assessment_id,student_id,attempt_id,paper_version_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id,paper_version_id),
    CONSTRAINT fk_prepared_supplement_actor FOREIGN KEY(actor_id) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_supplement_versions CHECK(approval_revision BETWEEN 1 AND 9007199254740991
        AND attempt_revision BETWEEN 1 AND 9007199254740991 AND declaration_revision>=1),
    CONSTRAINT ck_prepared_supplement_time CHECK(automatic_deadline=TIMESTAMPADD(HOUR,24,answering_ended_at)
        AND approved_at>=automatic_deadline AND upload_deadline>approved_at),
    CONSTRAINT ck_prepared_supplement_json CHECK(JSON_VALID(input_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
