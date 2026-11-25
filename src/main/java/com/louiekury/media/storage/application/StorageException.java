package com.louiekury.media.storage.application;

public class StorageException extends RuntimeException {
    private final StorageFailure reason;

    public StorageException(StorageFailure reason) {
        super(reason.name());
        this.reason = reason;
    }

    public StorageException(StorageFailure reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public StorageFailure getReason() { return reason; }
}
