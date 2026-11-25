package com.louiekury.media.validation.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum MediaType {
    JPEG("image/jpeg", 10_000_000L), PNG("image/png", 10_000_000L),
    WEBP("image/webp", 10_000_000L), MP4("video/mp4", 100_000_000L);

    // 타입과 매개변수의 문법을 검증한 뒤 기본 타입만 비교합니다.
    private static final String TOKEN = "[!#$%&'*+.^_`|~0-9A-Za-z-]++";
    private static final Pattern CONTENT_TYPE = Pattern.compile(
            "^[ \\t]*+(" + TOKEN + "/" + TOKEN + ")[ \\t]*+(?:;[ \\t]*+" + TOKEN
                    + "[ \\t]*+=[ \\t]*+(?:" + TOKEN + "|\"(?:[\\x20-\\x21\\x23-\\x5b\\x5d-\\x7e\\t]|\\\\[\\x20-\\x7e\\t])*+\")[ \\t]*+)*+$");

    private final String mimeType;
    private final long maxBytes;

    MediaType(String mimeType, long maxBytes) {
        this.mimeType = mimeType;
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() { return maxBytes; }
    public String getMimeType() { return mimeType; }

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
        Matcher matcher = CONTENT_TYPE.matcher(mimeType);
        if (!matcher.matches()) return null;
        String normalized = matcher.group(1).toLowerCase(Locale.ROOT);
        for (MediaType type : values()) {
            if (type.mimeType.equals(normalized)) return type;
        }
        return null;
    }
}
