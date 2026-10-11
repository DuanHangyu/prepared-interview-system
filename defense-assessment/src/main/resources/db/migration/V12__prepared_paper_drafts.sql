-- Prepared mode is additive. Never rewrite baseline migrations or legacy results.
CREATE TABLE prepared_paper_draft (
    assessment_id INT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_by INT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (assessment_id),
    CONSTRAINT fk_prepared_draft_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_draft_editor FOREIGN KEY (updated_by) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_draft_revision CHECK (revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_question_draft (
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    source VARCHAR(10) NOT NULL,
    body TEXT NOT NULL,
    ordinal INT NOT NULL,
    max_score INT NOT NULL,
    answer_seconds INT NOT NULL,
    rubric TEXT NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    audio_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    audio_object_key VARCHAR(1000) NULL,
    PRIMARY KEY (question_id),
    UNIQUE KEY uk_prepared_question_order (assessment_id, ordinal),
    CONSTRAINT fk_prepared_question_draft FOREIGN KEY (assessment_id) REFERENCES prepared_paper_draft(assessment_id),
    CONSTRAINT ck_prepared_question_source CHECK (source IN ('MANUAL','AI')),
    CONSTRAINT ck_prepared_question_ordinal CHECK (ordinal BETWEEN 1 AND 50),
    CONSTRAINT ck_prepared_question_score CHECK (max_score BETWEEN 1 AND 10000),
    CONSTRAINT ck_prepared_question_seconds CHECK (answer_seconds BETWEEN 1 AND 1800),
    CONSTRAINT ck_prepared_audio_state CHECK (audio_state IN ('PENDING','GENERATING','READY','FAILED','STALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE prepared_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    action VARCHAR(64) NOT NULL,
    resource_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_prepared_audit_resource (assessment_id, created_at),
    CONSTRAINT fk_prepared_audit_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_audit_actor FOREIGN KEY (actor_id) REFERENCES `user`(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
