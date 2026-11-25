package com.louiekury.media.storage.application.model;

import com.louiekury.media.validation.domain.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** 호출자는 읽기를 마친 뒤 이 객체를 닫아야 합니다. */
public record StoredObject(InputStream input, long size, MediaType type) implements AutoCloseable {
    public StoredObject {
        Objects.requireNonNull(input);
        Objects.requireNonNull(type);
        if (size < 0) throw new IllegalArgumentException("negative size");
    }

    @Override public void close() throws IOException { input.close(); }
}
