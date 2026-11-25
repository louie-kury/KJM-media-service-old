package com.louiekury.media.storage.application;

import com.louiekury.media.validation.application.model.ValidatedMedia;

public final class StoredCleanupException extends StorageException {
    private final ValidatedMedia media;

    public StoredCleanupException(ValidatedMedia media, Throwable cause) {
        super(StorageFailure.STORED_CLEANUP_FAILED, cause);
        this.media = media;
    }

    public ValidatedMedia getMedia() { return media; }
}
