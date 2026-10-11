-- Private prepared mode only. Costs are persisted before an external side effect.
CREATE TABLE prepared_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    created_by INT NOT NULL,
    kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_version BIGINT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_json JSON NOT NULL,
    output_json JSON NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
    attempts INT NOT NULL DEFAULT 0,
    lease_generation BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    external_sent BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    error_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_job_command (assessment_id,created_by,kind,idempotency_key),
    KEY idx_prepared_job_queue (kind,state,next_run_at,id),
    KEY idx_prepared_job_expired (kind,state,lease_until,id),
    CONSTRAINT fk_prepared_job_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_job_author FOREIGN KEY (created_by) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_job_kind CHECK (kind IN ('QUESTIONS','TTS','ASSEMBLE','TRANSCRIBE','SCORE')),
    CONSTRAINT ck_prepared_job_state CHECK (state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','NEEDS_REVIEW')),
    CONSTRAINT ck_prepared_job_version CHECK (input_version >= 0),
    CONSTRAINT ck_prepared_job_attempts CHECK (attempts BETWEEN 0 AND 3),
    CONSTRAINT ck_prepared_job_generation CHECK (lease_generation >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_budget (
    scope_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    cap_cny DECIMAL(12,6) NOT NULL,
    pause_cny DECIMAL(12,6) NOT NULL,
    spent_cny DECIMAL(12,6) NOT NULL DEFAULT 0,
    reserved_cny DECIMAL(12,6) NOT NULL DEFAULT 0,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    pause_reason VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (scope_id),
    CONSTRAINT ck_prepared_budget_limits CHECK (cap_cny BETWEEN 0 AND 100 AND pause_cny BETWEEN 0 AND 80 AND pause_cny <= cap_cny),
    CONSTRAINT ck_prepared_budget_amounts CHECK (spent_cny >= 0 AND reserved_cny >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_model_call (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    lease_generation BIGINT NOT NULL,
    scope_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reserved_cny DECIMAL(12,6) NOT NULL,
    actual_cny DECIMAL(12,6) NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RESERVED',
    provider_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_model_call_attempt (job_id,lease_generation),
    KEY idx_prepared_model_call_budget (scope_id,state),
    CONSTRAINT fk_prepared_call_job FOREIGN KEY (job_id) REFERENCES prepared_job(id),
    CONSTRAINT fk_prepared_call_budget FOREIGN KEY (scope_id) REFERENCES prepared_budget(scope_id),
    CONSTRAINT ck_prepared_call_state CHECK (state IN ('RESERVED','SENT','UNCERTAIN','SETTLED','CANCELLED')),
    CONSTRAINT ck_prepared_call_amount CHECK (reserved_cny > 0 AND (actual_cny IS NULL OR actual_cny >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_budget_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    scope_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    action VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    amount_cny DECIMAL(12,6) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_prepared_budget_event_scope (scope_id,created_at),
    CONSTRAINT fk_prepared_budget_event_scope FOREIGN KEY (scope_id) REFERENCES prepared_budget(scope_id),
    CONSTRAINT fk_prepared_budget_event_call FOREIGN KEY (call_id) REFERENCES prepared_model_call(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
