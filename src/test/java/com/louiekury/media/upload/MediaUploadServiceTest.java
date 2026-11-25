package com.louiekury.media.upload;

import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.StoredCleanupException;
import com.louiekury.media.upload.application.MediaUploadService;
import com.louiekury.media.upload.application.model.UploadJob;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.ValidationFailure;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaUploadServiceTest {
    private final MediaStorageService storage = mock(MediaStorageService.class);
    private final UploadRecordStore records = mock(UploadRecordStore.class);
    private final MediaUploadService service = new MediaUploadService(storage, records);
    private final UUID job = UUID.randomUUID();
    private final UUID uploader = UUID.randomUUID();
    private final MediaUsage usage = MediaUsage.POST_ATTACHMENT;
    private final ValidatedMedia validated = new ValidatedMedia(MediaType.PNG, 123);
    private final ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1});

    private UUID upload() { return service.upload(job, uploader, usage, "photo.png", "image/png", input); }

    @Test void preparesBeforeStorageAndCompletesWithValidatedMetadata() {
        when(storage.store(anyString(), eq(usage), eq("photo.png"), eq("image/png"), same(input)))
                .thenReturn(validated);
        UUID media = upload();
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(records, storage);
        order.verify(records).prepare(eq(job), key.capture(), eq(uploader), eq(usage));
        assertDoesNotThrow(() -> UUID.fromString(key.getValue()));
        order.verify(storage).store(eq(key.getValue()), eq(usage), eq("photo.png"), eq("image/png"), same(input));
        order.verify(records).markStored(job);
        order.verify(records).complete(job, media, "photo.png", validated);
    }

    @Test void failedPreparationNeverCallsStorage() {
        doThrow(new IllegalStateException("duplicate or database failure"))
                .when(records).prepare(eq(job), anyString(), eq(uploader), eq(usage));
        assertThrows(IllegalStateException.class, this::upload);
        verifyNoInteractions(storage);
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void unknownStorageOutcomeIsPreservedWithoutCompletion() {
        StorageException failure = new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(StorageException.class, this::upload));
        verify(records).markUnknown(job);
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void invalidFileEndsWorkWithoutCreatingMedia() {
        MediaValidationException failure = new MediaValidationException(ValidationFailure.EMPTY_FILE);
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(MediaValidationException.class, this::upload));
        verify(records).markFailed(job);
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void failedUnknownStateUpdateDoesNotHideOriginalFailure() {
        StorageException failure = new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(failure);
        doThrow(new IllegalStateException("database unavailable")).when(records).markUnknown(job);
        assertSame(failure, assertThrows(StorageException.class, this::upload));
        assertEquals(1, failure.getSuppressed().length);
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void confirmedStorageWithCleanupFailureStillRecordsCompletion() {
        StoredCleanupException failure = new StoredCleanupException(validated, new IllegalStateException());
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(failure);
        assertSame(failure, assertThrows(StoredCleanupException.class, this::upload));
        InOrder order = inOrder(records);
        order.verify(records).markStored(job);
        order.verify(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        verify(records, never()).markFailed(job);
    }

    @Test void failureMarkingStoredNeverReportsCompletion() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        doThrow(new IllegalStateException("database unavailable")).when(records).markStored(job);
        assertThrows(IllegalStateException.class, this::upload);
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void uncertainCompletionCommitDoesNotReturnSuccessOrStartAnotherUpload() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        IllegalStateException commitFailure = new IllegalStateException("commit result unknown");
        doThrow(commitFailure).when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        assertSame(commitFailure, assertThrows(IllegalStateException.class, this::upload));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(records, times(1)).prepare(eq(job), key.capture(), eq(uploader), eq(usage));
        verify(storage, times(1)).store(eq(key.getValue()), eq(usage), eq("photo.png"), eq("image/png"), same(input));
        verify(records).markStored(job);
        verify(records).find(job);
        verify(records, never()).markFailed(job);
        verify(records, never()).markUnknown(job);
    }

    @Test void uncertainCommitReturnsMediaIdOnlyWhenCompletedRecordMatches() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        AtomicReference<UUID> attempted = new AtomicReference<>();
        doAnswer(invocation -> { attempted.set(invocation.getArgument(1)); throw new IllegalStateException("lost response"); })
                .when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        when(records.find(job)).thenAnswer(invocation -> Optional.of(
                new UploadJob(job, "key", uploader, usage, UploadStatus.COMPLETED, attempted.get())));
        UUID result = upload();
        assertNotNull(attempted.get());
        assertEquals(attempted.get(), result);
    }

    @Test void mismatchedCompletedRecordPreservesOriginalCommitError() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        IllegalStateException failure = new IllegalStateException("commit unknown");
        doThrow(failure).when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        when(records.find(job)).thenReturn(Optional.of(new UploadJob(job, "key", uploader, usage,
                UploadStatus.COMPLETED, UUID.randomUUID())));
        assertSame(failure, assertThrows(IllegalStateException.class, this::upload));
    }

    @Test void storedButNotCompletedRecordPreservesOriginalCommitError() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        IllegalStateException failure = new IllegalStateException("commit failed");
        doThrow(failure).when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        when(records.find(job)).thenReturn(Optional.of(new UploadJob(job, "key", uploader, usage, UploadStatus.STORED, null)));
        assertSame(failure, assertThrows(IllegalStateException.class, this::upload));
    }

    @Test void failedReconciliationPreservesCommitAndLookupErrors() {
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenReturn(validated);
        IllegalStateException failure = new IllegalStateException("commit unknown");
        IllegalArgumentException lookup = new IllegalArgumentException("lookup unavailable");
        doThrow(failure).when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        when(records.find(job)).thenThrow(lookup);
        assertSame(failure, assertThrows(IllegalStateException.class, this::upload));
        assertArrayEquals(new Throwable[]{lookup}, failure.getSuppressed());
    }

    @Test void cleanupFailureIsStillReportedWhenCommitWasConfirmed() {
        StoredCleanupException cleanup = new StoredCleanupException(validated, new IllegalStateException());
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(cleanup);
        AtomicReference<UUID> attempted = new AtomicReference<>();
        doAnswer(invocation -> { attempted.set(invocation.getArgument(1)); throw new IllegalStateException("lost response"); })
                .when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        when(records.find(job)).thenAnswer(invocation -> Optional.of(
                new UploadJob(job, "key", uploader, usage, UploadStatus.COMPLETED, attempted.get())));
        assertSame(cleanup, assertThrows(StoredCleanupException.class, this::upload));
    }

    @Test void cleanupFailureIsSuppressedWhenCommitCannotBeConfirmed() {
        StoredCleanupException cleanup = new StoredCleanupException(validated, new IllegalStateException());
        when(storage.store(anyString(), any(), anyString(), anyString(), any())).thenThrow(cleanup);
        IllegalStateException failure = new IllegalStateException("commit unknown");
        doThrow(failure).when(records).complete(eq(job), any(UUID.class), eq("photo.png"), same(validated));
        assertSame(failure, assertThrows(IllegalStateException.class, this::upload));
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
    }

    @Test void filenameOf255CharactersIsAccepted() {
        String filename = "가".repeat(251) + ".png";
        when(storage.store(anyString(), eq(usage), eq(filename), eq("image/png"), same(input)))
                .thenReturn(validated);
        UUID media = service.upload(job, uploader, usage, filename, "image/png", input);
        verify(records).complete(job, media, filename, validated);
    }

    @Test void filenameOf256CharactersIsRejectedBeforeCreatingJobOrSaving() {
        String filename = "가".repeat(252) + ".png";
        StorageException error = assertThrows(StorageException.class,
                () -> service.upload(job, uploader, usage, filename, "image/png", input));
        assertEquals(StorageFailure.INPUT_INVALID, error.getReason());
        verifyNoInteractions(records, storage);
    }
}
