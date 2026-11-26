ALTER TABLE upload_job
    ADD COLUMN validated_filename VARCHAR(255) NULL,
    ADD COLUMN validated_content_type VARCHAR(100) NULL,
    ADD COLUMN validated_size BIGINT NULL,
    ADD COLUMN validated_sha256 VARCHAR(44) NULL,
    ADD CONSTRAINT chk_upload_job_validated_metadata CHECK (
        (validated_filename IS NULL AND validated_content_type IS NULL AND validated_size IS NULL AND validated_sha256 IS NULL)
        OR
        (validated_filename IS NOT NULL AND validated_content_type IS NOT NULL AND validated_size IS NOT NULL
            AND validated_sha256 IS NOT NULL AND validated_size > 0)
    );
