package com.louiekury.media.validation.application.service;

import com.louiekury.media.validation.domain.ValidationFailure;

public class MediaValidationException extends RuntimeException {
    private final ValidationFailure reason;

    public MediaValidationException(ValidationFailure reason) {
        super(reason.name());
        this.reason = reason;
    }

    public MediaValidationException(ValidationFailure reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public ValidationFailure getReason() { return reason; }
}
