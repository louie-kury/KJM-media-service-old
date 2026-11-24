package com.louiekury.media.validation.domain;

import java.util.Locale;

public enum MediaType {
    JPEG("image/jpeg", 10_000_000L), PNG("image/png", 10_000_000L),
    WEBP("image/webp", 10_000_000L), MP4("video/mp4", 100_000_000L);

    private final String mimeType;
    private final long maxBytes;

    MediaType(String mimeType, long maxBytes) {
        this.mimeType = mimeType;
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() { return maxBytes; }

    public static MediaType fromFilename(String filename) {
        if (filename == null) return null;
        int index = filename.lastIndexOf('.');
        if (index <= 0 || index == filename.length() - 1) return null;
        return switch (filename.substring(index + 1).toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg" -> JPEG;
            case "png" -> PNG;
            case "webp" -> WEBP;
            case "mp4" -> MP4;
            default -> null;
        };
    }

    public static MediaType fromContentType(String mimeType) {
        if (mimeType == null) return null;
        String normalized = mimeType.trim().toLowerCase(Locale.ROOT);
        for (MediaType type : values()) {
            if (type.mimeType.equals(normalized)) return type;
        }
        return null;
    }
}
