package com.louiekury.media.upload.application;

import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.StoredCleanupException;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** HTTP 계약을 제공하지 않는 내부 업로드 기능입니다. */
public final class MediaUploadService {
    private final MediaStorageService storage;
    private final UploadRecordStore records;

    public MediaUploadService(MediaStorageService storage, UploadRecordStore records) {
        this.storage = Objects.requireNonNull(storage);
        this.records = Objects.requireNonNull(records);
    }

    public UUID upload(UUID jobId, UUID uploaderId, MediaUsage usage, String filename,
                       String contentType, InputStream input) {
        Objects.requireNonNull(jobId);
        Objects.requireNonNull(uploaderId);
        Objects.requireNonNull(usage);
        if (filename != null && filename.codePointCount(0, filename.length()) > 255) {
            throw new StorageException(StorageFailure.INPUT_INVALID);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Upload must not run inside a DB transaction");
        }
        String key = UUID.randomUUID().toString();
        records.prepare(jobId, key, uploaderId, usage);

        ValidatedMedia validated;
        StoredCleanupException cleanupError = null;
        try {
            validated = storage.store(key, usage, filename, contentType, input);
        } catch (StoredCleanupException error) {
            validated = error.getMedia();
            cleanupError = error;
        } catch (MediaValidationException error) {
            markFailure(jobId, false, error);
            throw error;
        } catch (StorageException error) {
            markFailure(jobId, error.getReason() == StorageFailure.OUTCOME_UNKNOWN, error);
            throw error;
        } catch (RuntimeException | Error error) {
            markFailure(jobId, true, error);
            throw error;
        }

        UUID mediaId = UUID.randomUUID();
        try {
            records.markStored(jobId);
            records.complete(jobId, mediaId, filename, validated);
        } catch (RuntimeException | Error error) {
            boolean completed = false;
            try {
                completed = records.find(jobId)
                        .filter(job -> job.status() == UploadStatus.COMPLETED && mediaId.equals(job.mediaId()))
                        .isPresent();
            } catch (RuntimeException | Error lookupError) {
                error.addSuppressed(lookupError);
            }
            if (!completed) {
                if (cleanupError != null) error.addSuppressed(cleanupError);
                throw error;
            }
        }
        if (cleanupError != null) throw cleanupError;
        return mediaId;
    }

    private void markFailure(UUID jobId, boolean unknown, Throwable original) {
        try {
            if (unknown) records.markUnknown(jobId);
            else records.markFailed(jobId);
        } catch (RuntimeException error) {
            original.addSuppressed(error);
        }
    }
}
