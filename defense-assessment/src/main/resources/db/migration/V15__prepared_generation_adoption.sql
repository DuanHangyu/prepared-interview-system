CREATE TABLE prepared_generation_adoption (
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    idempotency_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    adopted_revision BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(assessment_id,actor_id,idempotency_key),
    FOREIGN KEY(assessment_id) REFERENCES assessment(id),
    FOREIGN KEY(actor_id) REFERENCES `user`(id),
    FOREIGN KEY(job_id) REFERENCES prepared_job(id),
    CHECK(adopted_revision > 0)
) ENGINE=InnoDB;

-- Immutable adoption evidence survives edits or deletion of the mutable draft question.
CREATE TABLE prepared_generation_adopted (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    actor_id INT NOT NULL,
    adopted_revision BIGINT NOT NULL,
    ordinal INT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(job_id,question_id),
    UNIQUE KEY uq_prepared_adoption_order(job_id,ordinal),
    KEY ix_prepared_adoption_assessment(assessment_id),
    FOREIGN KEY(job_id) REFERENCES prepared_job(id),
    FOREIGN KEY(assessment_id) REFERENCES assessment(id),
    FOREIGN KEY(actor_id) REFERENCES `user`(id),
    CHECK(adopted_revision > 0 AND ordinal BETWEEN 1 AND 20)
) ENGINE=InnoDB;
