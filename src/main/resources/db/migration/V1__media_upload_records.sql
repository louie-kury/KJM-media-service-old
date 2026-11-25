CREATE TABLE media (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid CHAR(36) NOT NULL,
    uploader_uuid CHAR(36) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    object_key VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_media_uuid UNIQUE (uuid),
    CONSTRAINT uk_media_object UNIQUE (object_key),
    INDEX idx_media_uploader_created (uploader_uuid, created_at)
);

CREATE TABLE upload_job (
    id CHAR(36) NOT NULL PRIMARY KEY,
    object_key VARCHAR(500) NOT NULL,
    uploader_uuid CHAR(36) NOT NULL,
    usage_name VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    media_uuid CHAR(36) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_upload_job_object UNIQUE (object_key)
);

CREATE TABLE temporary_attachment (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    media_id BIGINT NOT NULL,
    usage_name VARCHAR(30) NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_temporary_attachment_media UNIQUE (media_id),
    CONSTRAINT fk_temporary_attachment_media FOREIGN KEY (media_id) REFERENCES media (id)
);
