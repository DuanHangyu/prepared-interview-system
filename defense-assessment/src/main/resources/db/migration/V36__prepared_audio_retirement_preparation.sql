-- Metadata-only reversible preparation; no delete permit, object key or scheduler.
-- The existing parent lock serializes preparation/cancel with protection and reads.
CREATE TABLE prepared_audio_retirement_preparation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    prepared_at DATETIME(3) NOT NULL,
    receipt_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    cancellation_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    cancellation_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_retirement_preparation_key(assessment_id,student_id,attempt_id,idempotency_key),
    UNIQUE KEY uk_prepared_retirement_preparation_revision(assessment_id,student_id,attempt_id,revision),
    CONSTRAINT fk_prepared_retirement_preparation_attempt FOREIGN KEY(assessment_id,student_id,attempt_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id),
    CONSTRAINT ck_prepared_retirement_preparation CHECK(expected_revision BETWEEN 0 AND 9007199254740989
        AND revision=expected_revision+1 AND JSON_VALID(receipt_json)
        AND ((state='STAGED' AND cancellation_json IS NULL AND cancellation_sha256 IS NULL)
            OR (state='CANCELLED' AND cancellation_json IS NOT NULL AND JSON_VALID(cancellation_json) AND cancellation_sha256 IS NOT NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
