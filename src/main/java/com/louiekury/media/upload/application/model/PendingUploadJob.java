package com.louiekury.media.upload.application.model;

import java.time.Instant;

public record PendingUploadJob(UploadJob job, Instant updatedAt) { }
