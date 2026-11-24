package com.louiekury.media.validation.application.model;

import com.louiekury.media.validation.domain.MediaType;

public class ValidatedMedia {
    private final MediaType type;
    private final long size;

    public ValidatedMedia(MediaType type, long size) {
        this.type = type;
        this.size = size;
    }

    public MediaType getType() { return type; }
    public long getSize() { return size; }
}
