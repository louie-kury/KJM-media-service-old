package com.louiekury.media.upload.application.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingUploadCursor(Instant updatedAt, UUID id) {
    public PendingUploadCursor {
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(id, "id");
    }
}
