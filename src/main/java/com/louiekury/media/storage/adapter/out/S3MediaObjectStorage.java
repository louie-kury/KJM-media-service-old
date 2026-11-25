package com.louiekury.media.storage.adapter.out;

import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.validation.domain.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 내부 S3 연결부입니다. 버킷·권한 구성과 이 객체의 종료는 호출자 책임입니다. */
public final class S3MediaObjectStorage implements MediaObjectStorage, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    public S3MediaObjectStorage(S3ClientBuilder builder, String bucket) {
        if (builder == null || bucket == null || bucket.isBlank()) throw new StorageException(StorageFailure.INPUT_INVALID);
        this.bucket = bucket;
        // 첫 요청의 결과가 불명확한 상태에서 조건부 쓰기를 자동 재시도하지 않습니다.
        var config = builder.overrideConfiguration().toBuilder()
                .retryStrategy(retry -> retry.maxAttempts(1)).build();
        this.client = builder.overrideConfiguration(config).build();
    }

    @Override
    public void put(String key, Path validatedFile, long size, MediaType type, String sha256Base64) {
        requireKey(key);
        if (validatedFile == null || type == null || size <= 0 || size > type.getMaxBytes() || !validChecksum(sha256Base64)) {
            throw new StorageException(StorageFailure.INPUT_INVALID);
        }
        try {
            if (!Files.isRegularFile(validatedFile, LinkOption.NOFOLLOW_LINKS) || Files.size(validatedFile) != size) {
                throw new StorageException(StorageFailure.INPUT_INVALID);
            }
        } catch (IOException | SecurityException error) {
            throw new StorageException(StorageFailure.INPUT_INVALID, error);
        }
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key).contentLength(size)
                .contentType(contentType(type)).checksumSHA256(sha256Base64).ifNoneMatch("*").build();
        try {
            var response = client.putObject(request, RequestBody.fromFile(validatedFile));
            // 체크섬을 확인하지 않는 S3 호환 저장소는 동일한 내용이 저장됐음을 보장할 수 없습니다.
            if (!sha256Base64.equals(response.checksumSHA256())) throw new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        } catch (S3Exception error) {
            int status = error.statusCode();
            StorageFailure reason = status >= 400 && status < 500 && status != 408 && status != 409 && status != 429
                    ? StorageFailure.STORAGE_ERROR : StorageFailure.OUTCOME_UNKNOWN;
            throw new StorageException(reason, error);
        } catch (StorageException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new StorageException(StorageFailure.OUTCOME_UNKNOWN, error);
        }
    }

    @Override
    public StoredObject read(String key) {
        requireKey(key);
        try {
            var object = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            MediaType type = MediaType.fromContentType(object.response().contentType());
            Long size = object.response().contentLength();
            if (type == null || size == null || size <= 0 || size > type.getMaxBytes()) {
                object.abort();
                throw new StorageException(StorageFailure.STORAGE_ERROR);
            }
            return new StoredObject(object, size, type);
        } catch (S3Exception error) {
            if (error.awsErrorDetails() != null && "NoSuchKey".equals(error.awsErrorDetails().errorCode())) {
                throw new StorageException(StorageFailure.NOT_FOUND, error);
            }
            throw new StorageException(StorageFailure.STORAGE_ERROR, error);
        } catch (StorageException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new StorageException(StorageFailure.STORAGE_ERROR, error);
        }
    }

    @Override public void close() { client.close(); }

    private static boolean validChecksum(String checksum) {
        if (checksum == null) return false;
        try { return Base64.getDecoder().decode(checksum).length == 32; }
        catch (IllegalArgumentException error) { return false; }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new StorageException(StorageFailure.INPUT_INVALID);
    }

    private static String contentType(MediaType type) {
        return switch (type) {
            case JPEG -> "image/jpeg";
            case PNG -> "image/png";
            case WEBP -> "image/webp";
            case MP4 -> "video/mp4";
        };
    }
}
