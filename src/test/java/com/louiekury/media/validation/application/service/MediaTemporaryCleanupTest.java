package com.louiekury.media.validation.application.service;

import com.louiekury.media.validation.domain.ValidationFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaTemporaryCleanupTest {
    @TempDir Path temporary;

    private Path nonemptyDirectory() throws IOException {
        Path directory = Files.createDirectory(temporary.resolve("not-empty"));
        Files.writeString(directory.resolve("content"), "fixture");
        return directory;
    }

    @Test void cleanupFailureIsSuppressedWhenValidationAlreadyFailed() throws IOException {
        Path directory = nonemptyDirectory();
        var original = new MediaValidationException(ValidationFailure.FILE_TOO_LARGE);
        assertDoesNotThrow(() -> MediaValidationService.cleanupTemporaryFile(directory, original));
        assertEquals(ValidationFailure.FILE_TOO_LARGE, original.getReason());
        assertEquals(1, original.getSuppressed().length);
        assertInstanceOf(IOException.class, original.getSuppressed()[0]);
    }

    @Test void cleanupFailureAloneIsReportedAsWriteError() throws IOException {
        Path directory = nonemptyDirectory();
        var error = assertThrows(MediaValidationException.class,
                () -> MediaValidationService.cleanupTemporaryFile(directory, null));
        assertEquals(ValidationFailure.WRITE_ERROR, error.getReason());
        assertInstanceOf(IOException.class, error.getCause());
    }
}
