-- Irreversible intent only. No deletion or automatic activation/backfill.
-- Old preparation receipts and keys remain immutable and reversible only before intent.
ALTER TABLE prepared_audio_retirement_preparation
    ADD UNIQUE KEY uk_prepared_retirement_preparation_execution_scope(assessment_id,student_id,attempt_id,id,revision);

CREATE TABLE prepared_audio_retirement_execution (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    preparation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    started_at DATETIME(3) NOT NULL,
    inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    object_count INT NOT NULL,
    byte_size BIGINT NOT NULL,
    receipt_json TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_retirement_execution_scope(assessment_id,student_id,attempt_id),
    UNIQUE KEY uk_prepared_retirement_execution_key(assessment_id,student_id,attempt_id,idempotency_key),
    UNIQUE KEY uk_prepared_retirement_execution_preparation(preparation_id),
    CONSTRAINT fk_prepared_retirement_execution_preparation FOREIGN KEY(assessment_id,student_id,attempt_id,preparation_id,expected_revision)
        REFERENCES prepared_audio_retirement_preparation(assessment_id,student_id,attempt_id,id,revision),
    CONSTRAINT ck_prepared_retirement_execution CHECK(expected_revision BETWEEN 1 AND 9007199254740990
        AND revision=expected_revision+1 AND state='INTENT' AND JSON_VALID(receipt_json)
        AND object_count BETWEEN 1 AND 500050 AND byte_size BETWEEN 46 AND 33557787443200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
