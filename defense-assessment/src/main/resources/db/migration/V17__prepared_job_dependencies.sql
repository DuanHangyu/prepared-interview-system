-- A parent is queued without consuming attempts until every immutable dependency succeeds.
-- Both ends must belong to the same assessment and command creator; no cascade deletion.
ALTER TABLE prepared_job ADD UNIQUE KEY uk_prepared_job_scope (assessment_id,created_by,id);

CREATE TABLE prepared_job_dependency (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    depends_on_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    created_by INT NOT NULL,
    PRIMARY KEY (job_id,depends_on_id),
    KEY idx_prepared_dependency_child (depends_on_id,job_id),
    CONSTRAINT fk_prepared_dependency_parent FOREIGN KEY (assessment_id,created_by,job_id)
        REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT fk_prepared_dependency_child FOREIGN KEY (assessment_id,created_by,depends_on_id)
        REFERENCES prepared_job(assessment_id,created_by,id),
    CONSTRAINT ck_prepared_dependency_distinct CHECK (job_id <> depends_on_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
