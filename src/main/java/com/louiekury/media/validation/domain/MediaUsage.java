package com.louiekury.media.validation.domain;

public enum MediaUsage {
    MEMBER_PROFILE, PET_PROFILE, POST_ATTACHMENT, HEALTH_ATTACHMENT, CONSULTATION_ATTACHMENT;

    public boolean allows(MediaType type) {
        return type != null && (type != MediaType.MP4 || this == POST_ATTACHMENT);
    }
}
