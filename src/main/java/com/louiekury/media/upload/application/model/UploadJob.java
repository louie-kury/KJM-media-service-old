package com.louiekury.media.upload.application.model;

import com.louiekury.media.validation.domain.MediaUsage;
import java.util.UUID;

public record UploadJob(UUID id, String objectKey, UUID uploaderId, MediaUsage usage, UploadStatus status,
                        UUID mediaId, RecoveryMetadata recoveryMetadata) {
    public UploadJob(UUID id, String objectKey, UUID uploaderId, MediaUsage usage, UploadStatus status, UUID mediaId) {
        this(id, objectKey, uploaderId, usage, status, mediaId, null);
    }
}
