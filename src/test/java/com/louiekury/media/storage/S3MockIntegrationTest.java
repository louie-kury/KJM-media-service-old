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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
        return builder(endpoint);
    }

    private S3ClientBuilder builder(URI endpoint) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }

    @Test void storedObjectSurvivesLostResponseWithoutRetry() throws Exception {
        URI upstream = URI.create(System.getenv("MEDIA_TEST_S3_ENDPOINT"));
        // Validate before making any request, including the independent verification and cleanup.
        var validatedBuilder = localBuilder();
        String key = "integration-test/" + UUID.randomUUID() + "/lost-response.png";
        var requests = new AtomicInteger();
        try (S3Client direct = validatedBuilder.build();
             ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            proxy.setSoTimeout(5000);
            var observedStatus = CompletableFuture.supplyAsync(() -> {
                try (Socket incoming = proxy.accept(); Socket target = new Socket(upstream.getHost(), upstream.getPort())) {
                    requests.incrementAndGet();
                    target.setSoTimeout(5000);
                    var forward = CompletableFuture.runAsync(() -> {
                        try { incoming.getInputStream().transferTo(target.getOutputStream()); }
                        catch (IOException ignored) { /* both sockets are closed after the response arrives */ }
                    });
                    var header = new ByteArrayOutputStream();
                    int match = 0;
                    byte[] end = {'\r', '\n', '\r', '\n'};
                    while (match < end.length) {
                        int next = target.getInputStream().read();
                        if (next < 0 || header.size() > 16_384) throw new IOException("incomplete S3Mock response");
                        header.write(next);
                        match = next == end[match] ? match + 1 : (next == end[0] ? 1 : 0);
                    }
                    String result = header.toString(StandardCharsets.US_ASCII);
                    incoming.setSoLinger(true, 0);
                    incoming.close();
                    target.close();
                    forward.get(5, TimeUnit.SECONDS);
                    proxy.setSoTimeout(1000);
                    try (Socket unexpected = proxy.accept()) { requests.incrementAndGet(); }
                    catch (SocketTimeoutException expected) { /* no SDK retry */ }
                    return result;
                } catch (Exception error) { throw new IllegalStateException(error); }
            });
            try {
                URI faultEndpoint = URI.create("http://127.0.0.1:" + proxy.getLocalPort());
                try (var storage = new S3MediaObjectStorage(builder(faultEndpoint), BUCKET)) {
                    var service = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), storage);
                    var failure = assertThrows(StorageException.class, () -> service.store(key, MediaUsage.POST_ATTACHMENT,
                            "photo.png", "image/png", new ByteArrayInputStream(PNG)));
                    assertEquals(StorageFailure.OUTCOME_UNKNOWN, failure.getReason());
                }
                String response = observedStatus.get(8, TimeUnit.SECONDS);
                assertTrue(response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"), response);
                assertEquals(1, requests.get());
                var object = direct.getObjectAsBytes(request -> request.bucket(BUCKET).key(key));
                assertArrayEquals(PNG, object.asByteArray());
                assertEquals("image/png", object.response().contentType());
            } finally {
                direct.deleteObject(request -> request.bucket(BUCKET).key(key));
            }
            var removed = assertThrows(software.amazon.awssdk.services.s3.model.S3Exception.class,
                    () -> direct.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)));
            assertEquals(404, removed.statusCode(), "the test object must be removed");
        }
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

    @Test void observesStoredMetadataAndDistinguishesMissingKey() throws Exception {
        String prefix = "integration-test/" + UUID.randomUUID() + "/";
        String key = prefix + "observation.png";
        try (S3Client direct = localBuilder().build();
             var storage = new S3MediaObjectStorage(localBuilder(), BUCKET)) {
            var service = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), storage);
            try {
                service.store(key, MediaUsage.POST_ATTACHMENT, "photo.png", "image/png", new ByteArrayInputStream(PNG));
                var observed = storage.observe(key);
                assertEquals(PNG.length, observed.size());
                assertEquals("image/png", observed.contentType());
                if (observed.sha256() != null) {
                    String expected = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(PNG));
                    assertEquals(expected, observed.sha256());
                }
                assertArrayEquals(PNG, direct.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray());
                var missing = assertThrows(StorageException.class, () -> storage.observe(prefix + "missing.png"));
                assertEquals(StorageFailure.NOT_FOUND, missing.getReason());
            } finally { direct.deleteObject(request -> request.bucket(BUCKET).key(key)); }
        }
    }
}
