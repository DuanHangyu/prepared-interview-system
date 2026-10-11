-- Immutable preparation evidence. No FK to a mutable question row: editing/deleting a draft
-- must never remove an asset, published question or command receipt.
CREATE TABLE prepared_audio_asset (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'BUILDING',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    byte_size BIGINT NULL,
    duration_ms BIGINT NULL,
    media_type VARCHAR(64) NULL,
    verified_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_audio_identity (assessment_id, question_id, body_sha256, config_sha256),
    UNIQUE KEY uk_prepared_audio_object (object_key),
    UNIQUE KEY uk_prepared_audio_scope (assessment_id, id),
    CONSTRAINT fk_prepared_audio_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_audio_job FOREIGN KEY (job_id) REFERENCES prepared_job(id),
    CONSTRAINT ck_prepared_audio_asset_state CHECK (state IN ('BUILDING','READY','FAILED')),
    CONSTRAINT ck_prepared_audio_asset_ready CHECK (state <> 'READY' OR
        (object_key IS NOT NULL AND content_sha256 IS NOT NULL AND byte_size BETWEEN 45 AND 67108864
         AND duration_ms BETWEEN 1 AND 1800000 AND media_type='audio/wav' AND verified_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_paper_version (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    version_no INT NOT NULL,
    draft_revision BIGINT NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_json JSON NOT NULL,
    published_by INT NOT NULL,
    published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_paper_version_no (assessment_id, version_no),
    UNIQUE KEY uk_prepared_paper_version_scope (assessment_id, id),
    CONSTRAINT fk_prepared_version_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_version_actor FOREIGN KEY (published_by) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_version_number CHECK (version_no >= 1 AND draft_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_question_snapshot (
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ordinal INT NOT NULL,
    body TEXT NOT NULL,
    max_score INT NOT NULL,
    answer_seconds INT NOT NULL,
    rubric TEXT NOT NULL,
    audio_asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (paper_version_id, question_id),
    UNIQUE KEY uk_prepared_snapshot_order (paper_version_id, ordinal),
    CONSTRAINT fk_prepared_snapshot_paper FOREIGN KEY (assessment_id, paper_version_id)
        REFERENCES prepared_paper_version(assessment_id, id),
    CONSTRAINT fk_prepared_snapshot_audio FOREIGN KEY (assessment_id, audio_asset_id)
        REFERENCES prepared_audio_asset(assessment_id, id),
    CONSTRAINT ck_prepared_snapshot_rules CHECK (ordinal BETWEEN 1 AND 50
        AND max_score BETWEEN 1 AND 10000 AND answer_seconds BETWEEN 1 AND 1800)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_paper_gate (
    assessment_id INT NOT NULL,
    active_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    first_started_at DATETIME(3) NULL,
    PRIMARY KEY (assessment_id),
    CONSTRAINT fk_prepared_gate_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_gate_version FOREIGN KEY (assessment_id, active_version_id)
        REFERENCES prepared_paper_version(assessment_id, id),
    CONSTRAINT ck_prepared_gate_started CHECK (first_started_at IS NULL OR active_version_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_publication_receipt (
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    paper_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (assessment_id, actor_id, idempotency_key),
    CONSTRAINT fk_prepared_publication_version FOREIGN KEY (assessment_id, paper_version_id)
        REFERENCES prepared_paper_version(assessment_id, id),
    CONSTRAINT fk_prepared_publication_actor FOREIGN KEY (actor_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
