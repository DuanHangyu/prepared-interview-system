-- Forward-only: keep failed preparations and their receipts; never update published audio.
ALTER TABLE prepared_job ADD UNIQUE KEY uk_prepared_job_assessment (assessment_id,id);
ALTER TABLE prepared_audio_asset
    DROP INDEX uk_prepared_audio_identity,
    ADD COLUMN active_body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (CASE WHEN state IN ('BUILDING','READY') THEN body_sha256 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_prepared_audio_active (assessment_id,question_id,active_body_sha256,config_sha256),
    ADD COLUMN part_count INT NOT NULL DEFAULT 0,
    ADD COLUMN new_part_count INT NOT NULL DEFAULT 0,
    ADD COLUMN maximum_new_cost_cny DECIMAL(16,6) NOT NULL DEFAULT 0,
    ADD COLUMN sample_rate INT NULL,
    ADD COLUMN channels INT NULL,
    ADD COLUMN data_offset BIGINT NULL,
    ADD COLUMN data_size BIGINT NULL,
    ADD CONSTRAINT ck_prepared_audio_parts CHECK (part_count BETWEEN 0 AND 50 AND new_part_count BETWEEN 0 AND part_count AND maximum_new_cost_cny>=0),
    ADD CONSTRAINT fk_prepared_audio_scoped_job FOREIGN KEY (assessment_id,job_id) REFERENCES prepared_job(assessment_id,id);

CREATE TABLE prepared_audio_command (
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (assessment_id,actor_id,idempotency_key),
    CONSTRAINT fk_prepared_audio_command_asset FOREIGN KEY (assessment_id,asset_id) REFERENCES prepared_audio_asset(assessment_id,id),
    CONSTRAINT fk_prepared_audio_command_actor FOREIGN KEY (actor_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_audio_part (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_index INT NOT NULL,
    part_count INT NOT NULL,
    PRIMARY KEY (job_id),
    KEY ix_prepared_audio_part_cache (assessment_id,question_id,body_sha256,config_sha256,part_index),
    CONSTRAINT fk_prepared_audio_part_job FOREIGN KEY (assessment_id,job_id) REFERENCES prepared_job(assessment_id,id),
    CONSTRAINT ck_prepared_audio_part_order CHECK (part_index>=0 AND part_index<part_count AND part_count BETWEEN 1 AND 50)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_audio_part_ref (
    assessment_id INT NOT NULL,
    asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_index INT NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (asset_id,part_index),
    UNIQUE KEY uk_prepared_audio_ref_job (asset_id,job_id),
    CONSTRAINT fk_prepared_audio_ref_asset FOREIGN KEY (assessment_id,asset_id) REFERENCES prepared_audio_asset(assessment_id,id),
    CONSTRAINT fk_prepared_audio_ref_job FOREIGN KEY (assessment_id,job_id) REFERENCES prepared_job(assessment_id,id),
    CONSTRAINT ck_prepared_audio_ref_index CHECK (part_index BETWEEN 0 AND 49)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
