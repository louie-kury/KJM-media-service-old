package com.louiekury.media.upload.application.port.out;

import com.louiekury.media.upload.application.model.UploadJob;
import com.louiekury.media.upload.application.model.PendingUploadCursor;
import com.louiekury.media.upload.application.model.PendingUploadJob;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.Optional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UploadRecordStore {
    void prepare(UUID jobId, String objectKey, UUID uploaderId, MediaUsage usage);
    void markUnknown(UUID jobId);
    void markStored(UUID jobId);
    void markFailed(UUID jobId);
    void complete(UUID jobId, UUID mediaId, String filename, ValidatedMedia media);
    Optional<UploadJob> find(UUID jobId);
    List<PendingUploadJob> findPending(Instant cutoff, PendingUploadCursor cursor, int limit);
}
