package com.louiekury.media.validation.application.model;

import com.louiekury.media.validation.domain.MediaUsage;
import java.util.Map;
import java.util.Objects;

public record MediaPolicy(MediaUsage usage, int maxAttachments, Map<String, Long> maxBytesByMimeType) {
    public MediaPolicy {
        Objects.requireNonNull(usage);
        maxBytesByMimeType = Map.copyOf(maxBytesByMimeType);
    }
}
