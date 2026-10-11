-- Additive original IO/capability binding on the existing audit ledger.
-- No lifecycle transition, deletion, credential change or backfill is performed.
ALTER TABLE prepared_audit
    ADD COLUMN original_student_id INT NULL,
    ADD COLUMN original_attempt_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD KEY idx_prepared_audit_original_io(assessment_id,original_student_id,original_attempt_id,action,id),
    ADD CONSTRAINT fk_prepared_audit_original_attempt FOREIGN KEY(assessment_id,original_student_id,original_attempt_id)
        REFERENCES prepared_attempt(assessment_id,student_id,id),
    ADD CONSTRAINT fk_prepared_audit_original_answer FOREIGN KEY(assessment_id,original_student_id,original_attempt_id,resource_id)
        REFERENCES prepared_answer(assessment_id,student_id,attempt_id,id),
    ADD CONSTRAINT ck_prepared_audit_original_scope CHECK (
        (original_student_id IS NULL) = (original_attempt_id IS NULL)
        AND (action NOT IN ('ORIGINAL_PREVIEW_IO_OPENED','ORIGINAL_PREVIEW_IO_CLOSED',
             'ORIGINAL_TRANSCRIBE_IO_OPENED','ORIGINAL_TRANSCRIBE_IO_CLOSED',
             'ORIGINAL_ASSEMBLY_IO_OPENED','ORIGINAL_ASSEMBLY_IO_CLOSED','ORIGINAL_ASR_INPUT_AUTHORIZED')
          OR (original_student_id IS NOT NULL AND original_attempt_id IS NOT NULL
              AND resource_id IS NOT NULL AND resource_sha256 IS NOT NULL AND resource_version>0))
        AND (action<>'ORIGINAL_ASR_INPUT_AUTHORIZED' OR authorization_expires_at IS NOT NULL)
    );
