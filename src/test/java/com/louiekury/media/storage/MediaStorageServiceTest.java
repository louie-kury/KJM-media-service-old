package com.louiekury.media.storage;

import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MediaStorageServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private final RecordingStorage storage = new RecordingStorage();
    private final MediaStorageService service = new MediaStorageService(
            new MediaValidationService(new FileSignatureDetector()), storage);

    @Test void storesExactlyValidatedBytesWithSizeTypeAndDigestThenDeletesTemporaryFile() throws Exception {
        service.store("test/one", MediaUsage.POST_ATTACHMENT, "photo.png", "image/png", new ByteArrayInputStream(PNG));

        assertEquals(1, storage.putCalls);
        assertEquals("test/one", storage.key);
        assertArrayEquals(PNG, storage.bytes);
        assertEquals(PNG.length, storage.size);
        assertEquals(MediaType.PNG, storage.type);
        assertEquals(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(PNG)), storage.checksum);
        assertFalse(Files.exists(storage.path), "the service must remove its temporary file after storage");
    }

    @Test void invalidContentNeverReachesStorage() {
        assertThrows(MediaValidationException.class, () -> service.store("test/invalid", MediaUsage.POST_ATTACHMENT,
                "image.png", "image/png", new ByteArrayInputStream(new byte[]{1, 2, 3})));
        assertEquals(0, storage.putCalls);
    }

    @Test void emptyInputNeverReachesStorage() {
        assertThrows(MediaValidationException.class, () -> service.store("test/empty", MediaUsage.POST_ATTACHMENT,
                "image.png", "image/png", new ByteArrayInputStream(new byte[0])));
        assertEquals(0, storage.putCalls);
    }

    @Test void ambiguousStorageOutcomePropagatesAndTemporaryFileIsRemoved() {
        storage.failure = new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        StorageException error = assertThrows(StorageException.class, () -> service.store("test/ambiguous",
                MediaUsage.POST_ATTACHMENT, "image.png", "image/png", new ByteArrayInputStream(PNG)));
        assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
        assertEquals(1, storage.putCalls);
        assertFalse(Files.exists(storage.path));
    }

    @Test void successfulPutFollowedByCleanupFailureReportsStoredCleanupFailed() throws Exception {
        storage.breakCleanup = true;
        try {
            StorageException error = assertThrows(StorageException.class, () -> service.store("test/cleanup",
                    MediaUsage.POST_ATTACHMENT, "image.png", "image/png", new ByteArrayInputStream(PNG)));
            assertEquals(StorageFailure.STORED_CLEANUP_FAILED, error.getReason());
            assertEquals(1, storage.putCalls);
            assertArrayEquals(PNG, storage.bytes);
            assertTrue(Files.exists(storage.path));
        } finally {
            storage.removeBrokenCleanupDirectory();
        }
    }

    @Test void failedPutRemainsPrimaryWhenCleanupAlsoFails() throws Exception {
        storage.breakCleanup = true;
        storage.failure = new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        try {
            StorageException error = assertThrows(StorageException.class, () -> service.store("test/ambiguous-cleanup",
                    MediaUsage.POST_ATTACHMENT, "image.png", "image/png", new ByteArrayInputStream(PNG)));
            assertSame(storage.failure, error);
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
            assertEquals(1, error.getSuppressed().length);
            assertInstanceOf(IOException.class, error.getSuppressed()[0]);
        } finally {
            storage.removeBrokenCleanupDirectory();
        }
    }

    @Test void blankKeyIsRejectedBeforeInputOrStorageIsUsed() {
        StorageException error = assertThrows(StorageException.class, () -> service.store(" ", MediaUsage.POST_ATTACHMENT,
                "image.png", "image/png", new ByteArrayInputStream(PNG)));
        assertEquals(StorageFailure.INPUT_INVALID, error.getReason());
        assertEquals(0, storage.putCalls);
    }

    @Test void readReturnsExactContentAndCallerCanCloseStream() throws Exception {
        storage.readResult = new StoredObject(new ByteArrayInputStream(PNG), PNG.length, MediaType.PNG);
        try (StoredObject result = service.read("test/one")) {
            assertEquals(PNG.length, result.size());
            assertEquals(MediaType.PNG, result.type());
            assertArrayEquals(PNG, result.input().readAllBytes());
        }
        assertEquals("test/one", storage.readKey);
    }

    @Test void missingObjectIsNotTurnedIntoEmptySuccess() {
        storage.failure = new StorageException(StorageFailure.NOT_FOUND);
        StorageException error = assertThrows(StorageException.class, () -> service.read("test/missing"));
        assertEquals(StorageFailure.NOT_FOUND, error.getReason());
    }

    private static final class RecordingStorage implements MediaObjectStorage {
        @Override public com.louiekury.media.storage.application.model.ObjectObservation observe(String key) { throw new UnsupportedOperationException(); }
        int putCalls;
        String key, checksum, readKey;
        Path path;
        byte[] bytes;
        long size;
        MediaType type;
        StorageException failure;
        StoredObject readResult;
        boolean breakCleanup;

        @Override public void put(String key, Path file, long size, MediaType type, String sha256Base64) {
            putCalls++;
            this.key = key;
            this.path = file;
            this.size = size;
            this.type = type;
            this.checksum = sha256Base64;
            try { bytes = Files.readAllBytes(file); }
            catch (IOException error) { throw new AssertionError(error); }
            if (breakCleanup) {
                try {
                    Files.delete(file);
                    Files.createDirectory(file);
                    Files.write(file.resolve("prevent-delete"), new byte[]{1});
                } catch (IOException error) { throw new AssertionError(error); }
            }
            if (failure != null) throw failure;
        }

        @Override public StoredObject read(String key) {
            readKey = key;
            if (failure != null) throw failure;
            return readResult;
        }

        void removeBrokenCleanupDirectory() throws IOException {
            if (path != null && Files.isDirectory(path)) {
                Files.deleteIfExists(path.resolve("prevent-delete"));
                Files.deleteIfExists(path);
            }
        }
    }
}
