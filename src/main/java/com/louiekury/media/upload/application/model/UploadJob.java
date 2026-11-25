package com.louiekury.media.upload.application.model;

import com.louiekury.media.validation.domain.MediaUsage;
import java.util.UUID;

public record UploadJob(UUID id, String objectKey, UUID uploaderId, MediaUsage usage, UploadStatus status, UUID mediaId) { }
