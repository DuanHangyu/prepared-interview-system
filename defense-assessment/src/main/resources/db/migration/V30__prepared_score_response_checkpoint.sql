-- One synchronous paid SCORE send; credential-free input and credible paid reply are immutable.
-- A saved reply may be published after restart without another supplier request.
CREATE TABLE prepared_score_call (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_json JSON NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reply_json JSON NULL,
    reply_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    publication_failures INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_prepared_score_call (call_id),
    CONSTRAINT fk_prepared_score_call_job FOREIGN KEY (call_id,job_id) REFERENCES prepared_model_call(id,job_id),
    CONSTRAINT ck_prepared_score_reply CHECK ((reply_json IS NULL AND reply_sha256 IS NULL) OR (reply_json IS NOT NULL AND reply_sha256 IS NOT NULL)),
    CONSTRAINT ck_prepared_score_retry CHECK (publication_failures BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
