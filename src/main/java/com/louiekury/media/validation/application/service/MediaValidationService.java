package com.louiekury.media.validation.application.service;

import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.port.out.MediaContentDetector;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** 성공 전 출력은 임시 데이터입니다. 호출자는 실패 시 출력을 폐기해야 합니다.
 * 입력과 출력의 생명주기는 호출자가 관리합니다.
 */
public class MediaValidationService {
    private final MediaContentDetector detector;

    public MediaValidationService(MediaContentDetector detector) {
        this.detector = Objects.requireNonNull(detector);
    }

    public ValidatedMedia validate(MediaUsage usage, String filename, String contentType,
                                   InputStream input, OutputStream stagedOutput) {
        if (usage == null) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_USAGE);
        MediaType filenameType = MediaType.fromFilename(filename);
        MediaType declaredType = MediaType.fromContentType(contentType);
        if (filenameType == null || declaredType == null) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_TYPE);
        if (filenameType != declaredType) throw new MediaValidationException(ValidationFailure.TYPE_MISMATCH);
        if (!usage.allows(filenameType)) throw new MediaValidationException(ValidationFailure.UNSUPPORTED_TYPE);
        if (input == null) throw new MediaValidationException(ValidationFailure.READ_ERROR);
        if (stagedOutput == null) throw new MediaValidationException(ValidationFailure.WRITE_ERROR);

        if (filenameType == MediaType.MP4) {
            return validateVideo(filenameType, input, stagedOutput);
        }
        return validateContents(filenameType, input, stagedOutput, OutputStream.nullOutputStream());
    }

    private ValidatedMedia validateVideo(MediaType filenameType, InputStream input, OutputStream stagedOutput) {
        Path temporary;
        try {
            temporary = Files.createTempFile("media-video-", ".tmp");
        } catch (IOException error) {
            throw new MediaValidationException(ValidationFailure.WRITE_ERROR, error);
        }
        Throwable failure = null;
        try {
            ValidatedMedia result;
            try (OutputStream temporaryOutput = Files.newOutputStream(temporary)) {
                result = validateContents(filenameType, input, stagedOutput, temporaryOutput);
            }
            try {
                if (!detector.hasVideoTrack(temporary)) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
            } catch (IOException error) {
                throw new MediaValidationException(ValidationFailure.READ_ERROR, error);
            }
            return result;
        } catch (IOException error) {
            MediaValidationException wrapped = new MediaValidationException(ValidationFailure.WRITE_ERROR, error);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            cleanupTemporaryFile(temporary, failure);
        }
    }

    static void cleanupTemporaryFile(Path temporary, Throwable failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException | SecurityException error) {
            if (failure != null) failure.addSuppressed(error);
            else throw new MediaValidationException(ValidationFailure.WRITE_ERROR, error);
        }
    }

    private ValidatedMedia validateContents(MediaType filenameType, InputStream input, OutputStream stagedOutput,
                                            OutputStream temporaryOutput) {

        // 판별에 필요한 앞부분을 먼저 모아 불일치 파일을 출력에 쓰기 전에 거절합니다.
        byte[] prefix = new byte[4096];
        int prefixLength = 0;
        boolean eof = false;
        while (prefixLength < prefix.length) {
            int count = read(input, prefix, prefixLength, prefix.length - prefixLength);
            if (count < 0) {
                eof = true;
                break;
            }
            prefixLength += count;
        }
        if (prefixLength == 0) throw new MediaValidationException(ValidationFailure.EMPTY_FILE);
        MediaType actualType = detector.detect(prefix, prefixLength);
        if (actualType == null) throw new MediaValidationException(ValidationFailure.UNKNOWN_CONTENT);
        if (actualType != filenameType) throw new MediaValidationException(ValidationFailure.TYPE_MISMATCH);

        long size = prefixLength;
        write(stagedOutput, temporaryOutput, prefix, prefixLength);
        byte[] buffer = new byte[8192];
        while (!eof) {
            int count = read(input, buffer, 0, (int) Math.min(buffer.length, filenameType.getMaxBytes() + 1 - size));
            if (count < 0) break;
            size += count;
            if (size > filenameType.getMaxBytes()) throw new MediaValidationException(ValidationFailure.FILE_TOO_LARGE);
            write(stagedOutput, temporaryOutput, buffer, count);
        }
        return new ValidatedMedia(actualType, size);
    }

    private static int read(InputStream input, byte[] buffer, int offset, int length) {
        try {
            int count = input.read(buffer, offset, length);
            if (count == 0) {
                int one = input.read();
                if (one < 0) return -1;
                buffer[offset] = (byte) one;
                return 1;
            }
            return count;
        } catch (IOException error) {
            throw new MediaValidationException(ValidationFailure.READ_ERROR, error);
        }
    }

    private static void write(OutputStream stagedOutput, OutputStream temporaryOutput, byte[] buffer, int count) {
        try {
            stagedOutput.write(buffer, 0, count);
            temporaryOutput.write(buffer, 0, count);
        } catch (IOException error) {
            throw new MediaValidationException(ValidationFailure.WRITE_ERROR, error);
        }
    }
}
