package com.louiekury.media.storage;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in test: only an explicitly configured local S3Mock endpoint is allowed. */
@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_S3_ENDPOINT", matches = ".+")
class S3MockIntegrationTest {
    private static final String BUCKET = "codex-media-test";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    private S3ClientBuilder localBuilder() {
        URI endpoint = URI.create(System.getenv("MEDIA_TEST_S3_ENDPOINT"));
        if (!"http".equals(endpoint.getScheme()) ||
                !("localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost())) ||
                endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null ||
                endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("S3Mock test requires an explicit local HTTP endpoint");
        }
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }

    @Test void serviceRoundTripMissingAndConditionalOverwrite() throws Exception {
        String prefix = "integration-test/" + UUID.randomUUID() + "/";
        String key = prefix + "object.png";
        try (S3Client cleanup = localBuilder().build();
             var storage = new S3MediaObjectStorage(localBuilder(), BUCKET)) {
            var service = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), storage);
            try {
                service.store(key, MediaUsage.POST_ATTACHMENT, "photo.png", "image/png", new ByteArrayInputStream(PNG));
                try (var stored = service.read(key)) {
                    assertEquals(MediaType.PNG, stored.type());
                    assertEquals(PNG.length, stored.size());
                    assertArrayEquals(PNG, stored.input().readAllBytes());
                }
                StorageException missing = assertThrows(StorageException.class, () -> service.read(prefix + "missing.png"));
                assertEquals(StorageFailure.NOT_FOUND, missing.getReason());

                byte[] different = PNG.clone();
                different[different.length - 1] = 99;
                assertThrows(StorageException.class, () -> service.store(key, MediaUsage.POST_ATTACHMENT,
                        "photo.png", "image/png", new ByteArrayInputStream(different)));
                try (var stored = service.read(key)) {
                    assertArrayEquals(PNG, stored.input().readAllBytes(), "conditional write must not overwrite existing data");
                }
            } finally {
                cleanup.deleteObject(request -> request.bucket(BUCKET).key(key));
            }
        }
    }
}
