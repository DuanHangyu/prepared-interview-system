-- Human decisions are not model jobs or invented transcripts. Originals and AI history survive.
CREATE TABLE prepared_manual_score (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assessment_id INT NOT NULL,
    student_id INT NOT NULL,
    attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    answer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version_no INT NOT NULL,
    result_revision BIGINT NOT NULL,
    question_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_score INT NOT NULL,
    score_value DECIMAL(9,2) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    recorded_by INT NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    audio_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    transcript_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    score_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    previous_manual_score_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    input_json MEDIUMTEXT NOT NULL,
    input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(id),
    UNIQUE KEY uk_prepared_manual_version(answer_id,version_no),
    UNIQUE KEY uk_prepared_manual_revision(answer_id,result_revision),
    UNIQUE KEY uk_prepared_manual_scope(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_manual_answer FOREIGN KEY(answer_id) REFERENCES prepared_submission_answer(answer_id),
    CONSTRAINT fk_prepared_manual_original FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    CONSTRAINT fk_prepared_manual_actor FOREIGN KEY(recorded_by) REFERENCES `user`(id),
    CONSTRAINT fk_prepared_manual_transcript FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,transcript_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_manual_base_score FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,score_id)
        REFERENCES prepared_processing_result(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT fk_prepared_manual_previous FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,previous_manual_score_id)
        REFERENCES prepared_manual_score(assessment_id,student_id,attempt_id,answer_id,id),
    CONSTRAINT ck_prepared_manual_version CHECK(version_no BETWEEN 1 AND 100000),
    CONSTRAINT ck_prepared_manual_revision CHECK(result_revision BETWEEN 1 AND 9007199254740991),
    CONSTRAINT ck_prepared_manual_score CHECK(max_score>0 AND score_value>=0 AND score_value<=max_score),
    CONSTRAINT ck_prepared_manual_input CHECK(JSON_VALID(input_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE prepared_processing_head
    ADD COLUMN manual_score_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT fk_prepared_head_manual FOREIGN KEY(assessment_id,student_id,attempt_id,answer_id,manual_score_id)
        REFERENCES prepared_manual_score(assessment_id,student_id,attempt_id,answer_id,id),
    DROP CHECK ck_prepared_head_confirmation,
    ADD CONSTRAINT ck_prepared_head_confirmation CHECK(
        (confirmed_by IS NULL AND confirmed_at IS NULL) OR
        (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND (score_id IS NOT NULL OR manual_score_id IS NOT NULL)));

ALTER TABLE prepared_result_command
    DROP CHECK ck_prepared_result_command_action,
    ADD CONSTRAINT ck_prepared_result_command_action CHECK(action IN ('REGRADE','CONFIRM','ADJUST'));
