package com.louiekury.media.storage;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3ObjectObservationTest {
    private final S3Client client = mock(S3Client.class);
    private final S3ClientBuilder builder = mock(S3ClientBuilder.class, RETURNS_SELF);

    private S3MediaObjectStorage adapter() {
        when(builder.build()).thenReturn(client);
        when(builder.overrideConfiguration()).thenReturn(ClientOverrideConfiguration.builder().build());
        return new S3MediaObjectStorage(builder, "test-bucket");
    }

    @Test void returnsObservedMetadataAndRequestsChecksumWithoutChangingObject() {
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(10L).contentType("image/png").checksumSHA256(checksum).build());
        try (var storage = adapter()) {
            var observation = storage.observe("folder/photo.png");
            assertEquals(10L, observation.size());
            assertEquals("image/png", observation.contentType());
            assertEquals(checksum, observation.sha256());
            var request = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(client).headObject(request.capture());
            assertEquals("test-bucket", request.getValue().bucket());
            assertEquals("folder/photo.png", request.getValue().key());
            assertEquals(ChecksumMode.ENABLED, request.getValue().checksumMode());
            verifyNoMoreInteractions(client);
        }
    }

    @Test void missingChecksumIsExplicitlyUnknown() {
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength(1L).contentType("image/png").build());
        try (var storage = adapter()) {
            assertNull(storage.observe("photo.png").sha256());
        }
    }

    @Test void blankOrNullKeysNeverContactStorage() {
        try (var storage = adapter()) {
            for (String key : new String[]{null, "", "  "}) {
                assertEquals(StorageFailure.INPUT_INVALID,
                        assertThrows(StorageException.class, () -> storage.observe(key)).getReason());
            }
            verifyNoInteractions(client);
        }
    }

    @Test void ambiguousMissingDeniedAndTransportFailuresAreNotReportedAsAbsence() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).build())
                .thenThrow(S3Exception.builder().statusCode(403).build())
                .thenThrow(S3Exception.builder().statusCode(500).build())
                .thenThrow(SdkClientException.create("connection timed out"));
        when(client.getObject(any(GetObjectRequest.class))).thenThrow(error(404, "NoSuchBucket"));
        try (var storage = adapter()) {
            for (int i = 0; i < 4; i++) {
                assertEquals(StorageFailure.STORAGE_ERROR,
                        assertThrows(StorageException.class, () -> storage.observe("key")).getReason());
            }
        }
    }

    @Test void missingOrInvalidSizeIsNotPresentedAsReliableMetadata() {
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentType("image/png").build())
                .thenReturn(HeadObjectResponse.builder().contentLength(-1L).contentType("image/png").build());
        try (var storage = adapter()) {
            for (int i = 0; i < 2; i++) {
                assertEquals(StorageFailure.STORAGE_ERROR,
                        assertThrows(StorageException.class, () -> storage.observe("key")).getReason());
            }
        }
    }

    @Test void afterHead404OnlyExplicitGetNoSuchKeyConfirmsAbsence() {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        when(client.getObject(any(GetObjectRequest.class)))
                .thenThrow(error(404, "NoSuchKey"))
                .thenThrow(error(404, "NoSuchBucket"))
                .thenThrow(error(404, null))
                .thenThrow(error(403, "AccessDenied"))
                .thenThrow(SdkClientException.create("timeout"));
        try (var storage = adapter()) {
            assertEquals(StorageFailure.NOT_FOUND, assertThrows(StorageException.class, () -> storage.observe("key")).getReason());
            for (int i = 0; i < 4; i++) {
                assertEquals(StorageFailure.STORAGE_ERROR, assertThrows(StorageException.class, () -> storage.observe("key")).getReason());
            }
        }
    }

    @Test void delayedWriteAfterHead404IsObservedAndStreamIsAborted() throws Exception {
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
        when(response.response()).thenReturn(GetObjectResponse.builder().contentLength(12L)
                .contentType("image/png").checksumSHA256("raw-checksum").build());
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);
        try (var storage = adapter()) {
            var result = storage.observe("key");
            assertEquals(12L, result.size());
            assertEquals("image/png", result.contentType());
            assertEquals("raw-checksum", result.sha256());
            var request = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(client).getObject(request.capture());
            assertEquals(ChecksumMode.ENABLED, request.getValue().checksumMode());
            assertNull(request.getValue().range());
            verify(response).abort();
            verify(response, never()).read();
        }
    }

    private static S3Exception error(int status, String code) {
        return (S3Exception) S3Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }
}
