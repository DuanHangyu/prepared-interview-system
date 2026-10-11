-- Independent protection revision, strongly bound to the immutable original attempt.
-- No deletion, scheduler, retention anchor changes or destructive backfill.
CREATE TABLE prepared_retention_command (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id INT NOT NULL,
    action VARCHAR(16) NOT NULL,
    expected_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL,
    held BOOLEAN NOT NULL,
    executed_at DATETIME(3) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    audio_state VARCHAR(16) NOT NULL,
    results_state VARCHAR(16) NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    receipt_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_retention_command(assessment_id,actor_id,idempotency_key),
    UNIQUE KEY uk_prepared_retention_command_scope(assessment_id,student_id,attempt_id,id),
    UNIQUE KEY uk_prepared_retention_command_revision(assessment_id,student_id,attempt_id,revision),
    CONSTRAINT fk_prepared_retention_command_attempt FOREIGN KEY(assessment_id,student_id,attempt_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id),
    CONSTRAINT fk_prepared_retention_command_actor FOREIGN KEY(actor_id) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_retention_command CHECK(expected_revision BETWEEN 0 AND 9007199254740990
        AND revision=expected_revision+1 AND held IN (0,1)
        AND ((action='PROTECT' AND held=1) OR (action='RELEASE' AND held=0))
        AND CHAR_LENGTH(reason) BETWEEN 1 AND 1000
        AND audio_state IN ('RETAINED','RETIRED') AND results_state='RETAINED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_retention_guard (
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    held BOOLEAN NOT NULL DEFAULT FALSE,
    last_command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    audio_state VARCHAR(16) NOT NULL DEFAULT 'RETAINED',
    results_state VARCHAR(16) NOT NULL DEFAULT 'RETAINED',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY(attempt_id),
    CONSTRAINT fk_prepared_retention_guard_attempt FOREIGN KEY(assessment_id,student_id,attempt_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id),
    CONSTRAINT fk_prepared_retention_guard_command FOREIGN KEY(assessment_id,student_id,attempt_id,last_command_id)
        REFERENCES prepared_retention_command(assessment_id,student_id,attempt_id,id),
    CONSTRAINT ck_prepared_retention_guard CHECK(revision BETWEEN 0 AND 9007199254740991
        AND held IN (0,1) AND (held=0 OR last_command_id IS NOT NULL)
        AND audio_state IN ('RETAINED','RETIRING','RETIRED') AND results_state IN ('RETAINED','RETIRING')
        AND (held=0 OR (audio_state<>'RETIRING' AND results_state<>'RETIRING'))
        AND updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
