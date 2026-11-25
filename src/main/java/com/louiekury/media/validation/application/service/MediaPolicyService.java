package com.louiekury.media.validation.application.service;

import com.louiekury.media.validation.application.model.MediaPolicy;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public final class MediaPolicyService {
    public MediaPolicy getPolicy(MediaUsage usage) {
        Objects.requireNonNull(usage, "usage");
        int maxAttachments = switch (usage) {
            case MEMBER_PROFILE, PET_PROFILE -> 1;
            case POST_ATTACHMENT -> 5;
            case HEALTH_ATTACHMENT, CONSULTATION_ATTACHMENT -> 3;
        };
        var limits = Arrays.stream(MediaType.values())
                .filter(usage::allows)
                .collect(Collectors.toUnmodifiableMap(MediaType::getMimeType, MediaType::getMaxBytes));
        return new MediaPolicy(usage, maxAttachments, limits);
    }
}
