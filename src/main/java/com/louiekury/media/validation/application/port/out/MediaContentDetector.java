package com.louiekury.media.validation.application.port.out;

import com.louiekury.media.validation.domain.MediaType;
import java.io.IOException;
import java.nio.file.Path;

public interface MediaContentDetector {
    MediaType detect(byte[] prefix, int length);
    /** 실제 영상 트랙을 확인할 수 없으면 허용하지 않습니다. */
    default boolean hasVideoTrack(Path file) throws IOException { return false; }
}
