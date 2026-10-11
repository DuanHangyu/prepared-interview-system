-- Do not reference the mutable question row: deleting a draft must not erase review evidence.
CREATE TABLE prepared_question_review (
    id BIGINT NOT NULL AUTO_INCREMENT,
    assessment_id INT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reviewer_id INT NOT NULL,
    reviewed_revision BIGINT NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reviewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_prepared_review_version (assessment_id, reviewed_revision, question_id),
    CONSTRAINT fk_prepared_review_assessment FOREIGN KEY (assessment_id) REFERENCES assessment(id),
    CONSTRAINT fk_prepared_reviewer FOREIGN KEY (reviewer_id) REFERENCES `user`(id),
    CONSTRAINT ck_prepared_review_revision CHECK (reviewed_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
