-- Audit the exact original content and bounded capability, never a signed URL or raw answer.
-- Nullable additive columns preserve all prior audit events; audit survives media retirement.
ALTER TABLE prepared_audit
    ADD COLUMN resource_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN resource_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN authorization_expires_at DATETIME(3) NULL,
    ADD CONSTRAINT ck_prepared_original_audio_audit CHECK (
        action <> 'ORIGINAL_AUDIO_AUTHORIZED' OR
        (resource_id IS NOT NULL AND resource_sha256 IS NOT NULL AND authorization_expires_at IS NOT NULL)
    );
