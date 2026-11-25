package com.louiekury.media.storage.application.port.out;

import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.validation.domain.MediaType;
import java.nio.file.Path;

public interface MediaObjectStorage {
    /** 파일은 호출자가 소유하고 검증한 SHA-256은 Base64 형식입니다. */
    void put(String key, Path validatedFile, long size, MediaType type, String sha256Base64);

    /** 반환한 읽기 스트림은 호출자가 닫아야 합니다. */
    StoredObject read(String key);
}
