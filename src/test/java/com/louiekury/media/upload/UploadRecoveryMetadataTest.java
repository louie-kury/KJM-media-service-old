package com.louiekury.media.upload;

import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.upload.application.MediaUploadService;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UploadRecoveryMetadataTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private final UploadRecordStore records = mock(UploadRecordStore.class);
    private final MediaObjectStorage objects = mock(MediaObjectStorage.class);
    private final MediaUploadService upload = new MediaUploadService(
            new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), objects), records);
    private final UUID job = UUID.randomUUID();

    private void upload(byte[] content) {
        upload.upload(job, UUID.randomUUID(), MediaUsage.POST_ATTACHMENT, "photo.png", "image/png",
                new ByteArrayInputStream(content));
    }

    @Test void recordsValidatedMetadataBeforePutWithSameSizeTypeAndDigest() throws Exception {
        upload(PNG);
        var media = ArgumentCaptor.forClass(ValidatedMedia.class);
        var digest = ArgumentCaptor.forClass(String.class);
        var order = inOrder(records, objects);
        order.verify(records).prepare(eq(job), anyString(), any(), eq(MediaUsage.POST_ATTACHMENT));
        order.verify(records).recordValidated(eq(job), eq("photo.png"), media.capture(), digest.capture());
        String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(PNG));
        assertEquals(MediaType.PNG, media.getValue().getType());
        assertEquals(PNG.length, media.getValue().getSize());
        assertEquals(expected, digest.getValue());
        order.verify(objects).put(anyString(), any(Path.class), eq((long) PNG.length), eq(MediaType.PNG), eq(expected));
        order.verify(records).markStored(job);
    }

    @Test void invalidOrEmptyInputNeverRecordsMetadataOrCallsPut() {
        assertThrows(MediaValidationException.class, () -> upload(new byte[0]));
        verify(records, never()).recordValidated(any(), any(), any(), any());
        verifyNoInteractions(objects);
    }

    @Test void databaseFailureBeforePutPreservesPreparedStateAndPropagates() {
        var failure = new IllegalStateException("commit result unknown");
        doThrow(failure).when(records).recordValidated(eq(job), anyString(), any(), anyString());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> upload(PNG)));
        verifyNoInteractions(objects);
        verify(records, never()).markUnknown(any());
        verify(records, never()).markFailed(any());
        verify(records, never()).markStored(any());
        verify(records, never()).complete(any(), any(), any(), any());
    }

    @Test void unknownPutOutcomeKeepsRecordedEvidenceAndDoesNotComplete() {
        var failure = new StorageException(StorageFailure.OUTCOME_UNKNOWN);
        doThrow(failure).when(objects).put(anyString(), any(Path.class), anyLong(), any(), anyString());
        assertSame(failure, assertThrows(StorageException.class, () -> upload(PNG)));
        var order = inOrder(records, objects);
        order.verify(records).recordValidated(eq(job), eq("photo.png"), any(), anyString());
        order.verify(objects).put(anyString(), any(Path.class), anyLong(), any(), anyString());
        order.verify(records).markUnknown(job);
        verify(records, never()).complete(any(), any(), any(), any());
    }
}
