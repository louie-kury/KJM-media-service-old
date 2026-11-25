package com.louiekury.media.storage.application;

import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/** 외부 API가 아닌 내부 저장 기능입니다. 입력 스트림은 호출자가 닫습니다. */
public class MediaStorageService {
    private final MediaValidationService validator;
    private final MediaObjectStorage storage;

    public MediaStorageService(MediaValidationService validator, MediaObjectStorage storage) {
        this.validator = Objects.requireNonNull(validator);
        this.storage = Objects.requireNonNull(storage);
    }

    public void store(String key, MediaUsage usage, String filename, String contentType, InputStream input) {
        requireKey(key);
        if (input == null) throw new StorageException(StorageFailure.INPUT_INVALID);
        Path temporary;
        try { temporary = Files.createTempFile("media-storage-", ".tmp"); }
        catch (IOException error) { throw new StorageException(StorageFailure.STORAGE_ERROR, error); }

        Throwable failure = null;
        boolean stored = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ValidatedMedia validated;
            try (var output = new DigestOutputStream(Files.newOutputStream(temporary), digest)) {
                validated = validator.validate(usage, filename, contentType, input, output);
            }
            storage.put(key, temporary, validated.getSize(), validated.getType(),
                    Base64.getEncoder().encodeToString(digest.digest()));
            stored = true;
        } catch (IOException | NoSuchAlgorithmException error) {
            StorageException wrapped = new StorageException(StorageFailure.STORAGE_ERROR, error);
            failure = wrapped;
            throw wrapped;
        } catch (RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            cleanupTemporary(temporary, failure, stored);
        }
    }

    static void cleanupTemporary(Path temporary, Throwable failure, boolean stored) {
        try { Files.deleteIfExists(temporary); }
        catch (IOException | SecurityException error) {
            if (failure != null) failure.addSuppressed(error);
            else throw new StorageException(stored ? StorageFailure.STORED_CLEANUP_FAILED : StorageFailure.STORAGE_ERROR, error);
        }
    }

    public StoredObject read(String key) {
        requireKey(key);
        return storage.read(key);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) throw new StorageException(StorageFailure.INPUT_INVALID);
    }
}
