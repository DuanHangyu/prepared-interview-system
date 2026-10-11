-- One paid submit per job, followed by recoverable non-paid status/result reads.
-- Neither credentials nor signed input/output URLs belong in these snapshots.
ALTER TABLE prepared_model_call ADD UNIQUE KEY uk_prepared_call_job_identity (id,job_id);
CREATE TABLE prepared_async_model_task (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_json JSON NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    phase VARCHAR(16) NOT NULL DEFAULT 'DISPATCHING',
    result_json JSON NULL,
    result_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    recovery_deadline DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (job_id),
    UNIQUE KEY uk_prepared_async_call (call_id),
    UNIQUE KEY uk_prepared_async_provider_task (task_id),
    CONSTRAINT fk_prepared_async_call_job FOREIGN KEY (call_id,job_id) REFERENCES prepared_model_call(id,job_id),
    CONSTRAINT ck_prepared_async_phase CHECK (
        (phase='DISPATCHING' AND task_id IS NULL AND result_json IS NULL AND result_sha256 IS NULL)
        OR (phase='POLLING' AND task_id IS NOT NULL AND result_json IS NULL AND result_sha256 IS NULL)
        OR (phase='RESULT_READY' AND task_id IS NOT NULL AND result_json IS NOT NULL AND result_sha256 IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
