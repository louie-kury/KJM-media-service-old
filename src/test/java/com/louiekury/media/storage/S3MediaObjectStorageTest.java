package com.louiekury.media.storage;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.validation.domain.MediaType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3MediaObjectStorageTest {
    private final S3Client client = mock(S3Client.class);
    private final S3ClientBuilder builder = mock(S3ClientBuilder.class, RETURNS_SELF);

    private S3MediaObjectStorage adapter() {
        when(builder.build()).thenReturn(client);
        when(builder.overrideConfiguration()).thenReturn(ClientOverrideConfiguration.builder().build());
        return new S3MediaObjectStorage(builder, "test-bucket");
    }

    @Test void putRequiresMatchingChecksumAndProtectsExistingKey(@TempDir Path directory) throws Exception {
        byte[] data = {1, 2, 3, 4};
        Path file = Files.write(directory.resolve("file"), data);
        String checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().checksumSHA256(checksum).build());
        try (var adapter = adapter()) {
            adapter.put("test/key", file, data.length, MediaType.PNG, checksum);
            ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client).putObject(request.capture(), any(RequestBody.class));
            assertEquals("test-bucket", request.getValue().bucket());
            assertEquals("test/key", request.getValue().key());
            assertEquals("*", request.getValue().ifNoneMatch());
            assertEquals(checksum, request.getValue().checksumSHA256());
            assertEquals("image/png", request.getValue().contentType());
            assertEquals(data.length, request.getValue().contentLength());
        }
    }

    @Test void deniedWriteIsNotReportedAsSuccess(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("file"), new byte[]{1});
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().statusCode(403).build());
        try (var adapter = adapter()) {
            var error = assertThrows(StorageException.class, () -> adapter.put("key", file, 1, MediaType.PNG, checksum));
            assertEquals(StorageFailure.STORAGE_ERROR, error.getReason());
        }
    }

    @Test void transportFailureDuringPutHasUnknownOutcome(@TempDir Path directory) throws Exception {
        Path file = Files.write(directory.resolve("file"), new byte[]{1});
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection failed"));
        try (var adapter = adapter()) {
            var error = assertThrows(StorageException.class, () -> adapter.put("key", file, 1, MediaType.PNG, checksum));
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
        }
    }

    @Test void missingObjectAndAccessDeniedReadRemainDistinct() {
        try (var adapter = adapter()) {
            S3Exception.Builder missingBuilder = S3Exception.builder();
            missingBuilder.statusCode(404);
            missingBuilder.awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build());
            S3Exception missing = (S3Exception) missingBuilder.build();
            S3Exception.Builder deniedBuilder = S3Exception.builder();
            deniedBuilder.statusCode(403);
            S3Exception denied = (S3Exception) deniedBuilder.build();
            when(client.getObject(any(GetObjectRequest.class))).thenThrow(missing).thenThrow(denied);
            assertEquals(StorageFailure.NOT_FOUND, assertThrows(StorageException.class, () -> adapter.read("missing")).getReason());
            assertEquals(StorageFailure.STORAGE_ERROR, assertThrows(StorageException.class, () -> adapter.read("denied")).getReason());
        }
    }

    @Test void inputSizeMismatchNeverCallsS3(@TempDir Path directory) throws IOException {
        Path file = Files.write(directory.resolve("file"), new byte[]{1, 2});
        String checksum = Base64.getEncoder().encodeToString(new byte[32]);
        try (var adapter = adapter()) {
            assertEquals(StorageFailure.INPUT_INVALID,
                    assertThrows(StorageException.class, () -> adapter.put("key", file, 1, MediaType.PNG, checksum)).getReason());
            verify(client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }
    }

    @Test void invalidObjectMetadataAbortsWithoutClosingResponse() throws Exception {
        record Metadata(String contentType, Long size) {}
        for (Metadata metadata : new Metadata[]{
                new Metadata(null, 1L),
                new Metadata("application/octet-stream", 1L),
                new Metadata("image/png", null),
                new Metadata("image/png", 0L),
                new Metadata("image/png", 10_000_001L)
        }) {
            @SuppressWarnings("unchecked")
            ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
            when(response.response()).thenReturn(GetObjectResponse.builder()
                    .contentType(metadata.contentType()).contentLength(metadata.size()).build());
            when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);
            try (var adapter = adapter()) {
                StorageException error = assertThrows(StorageException.class, () -> adapter.read("key"));
                assertEquals(StorageFailure.STORAGE_ERROR, error.getReason());
                verify(response).abort();
                verify(response, never()).close();
                verify(response, never()).read();
            }
        }
    }

    @Test void validObjectReturnsStreamForCallerToClose() throws Exception {
        @SuppressWarnings("unchecked")
        ResponseInputStream<GetObjectResponse> response = mock(ResponseInputStream.class);
        when(response.response()).thenReturn(GetObjectResponse.builder()
                .contentType("image/png").contentLength(10_000_000L).build());
        when(client.getObject(any(GetObjectRequest.class))).thenReturn(response);
        try (var adapter = adapter()) {
            try (var stored = adapter.read("key")) {
                assertSame(response, stored.input());
                assertEquals(MediaType.PNG, stored.type());
                assertEquals(10_000_000L, stored.size());
                verify(response, never()).abort();
                verify(response, never()).close();
            }
            verify(response).close();
            verify(response, never()).abort();
        }
    }
}
