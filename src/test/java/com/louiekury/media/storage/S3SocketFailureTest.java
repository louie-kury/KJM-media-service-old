package com.louiekury.media.storage;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK requests against an isolated local HTTP fault endpoint, not AWS or S3Mock. */
class S3SocketFailureTest {
    private static final byte[] DATA = {1, 2, 3, 4, 5, 6};

    private S3MediaObjectStorage storage(int port) {
        return storage(port, Duration.ofSeconds(3));
    }

    private S3MediaObjectStorage storage(int port, Duration timeout) {
        var builder = S3Client.builder().endpointOverride(URI.create("http://127.0.0.1:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .overrideConfiguration(c -> c.apiCallTimeout(timeout));
        return new S3MediaObjectStorage(builder, "test-bucket");
    }

    private HttpServer localServer() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
    }

    private String checksum() throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(DATA));
    }

    private byte[] decodeAwsChunks(byte[] body) throws IOException {
        var input = new ByteArrayInputStream(body);
        var decoded = new ByteArrayOutputStream();
        while (true) {
            var line = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1 && next != '\r') line.write(next);
            if (next == -1 || input.read() != '\n') throw new IOException("invalid chunk header");
            String length = line.toString(StandardCharsets.US_ASCII).split(";", 2)[0];
            int size = Integer.parseInt(length, 16);
            if (size == 0) return decoded.toByteArray();
            byte[] chunk = input.readNBytes(size);
            if (chunk.length != size || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("incomplete chunk");
            }
            decoded.write(chunk);
        }
    }

    @Test void realHead404ThenMissingBucketIsNotReportedAsMissingObject() throws Exception {
        var headCount = new AtomicInteger();
        var getCount = new AtomicInteger();
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
            } else {
                getCount.incrementAndGet();
                byte[] body = "<Error><Code>NoSuchBucket</Code><Message>Missing bucket</Message></Error>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/xml");
                exchange.sendResponseHeaders(404, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort())) {
            assertEquals(StorageFailure.STORAGE_ERROR,
                    assertThrows(StorageException.class, () -> adapter.observe("key")).getReason());
            assertEquals(1, headCount.get());
            assertEquals(1, getCount.get());
        } finally { server.stop(0); }
    }

    @Test void requestBodyReceivedThenConnectionClosedIsUnknownAndNotRetried(@TempDir Path dir) throws Exception {
        var requests = new AtomicInteger();
        var received = new AtomicReference<byte[]>();
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            exchange.close();
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort())) {
            Path file = Files.write(dir.resolve("upload"), DATA);
            var error = assertThrows(StorageException.class,
                    () -> adapter.put("key", file, DATA.length, MediaType.PNG, checksum()));
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
            assertArrayEquals(DATA, decodeAwsChunks(received.get()),
                    "failure must occur after receiving the complete request");
            assertEquals(1, requests.get(), "uncertain writes must not be automatically retried");
        } finally { server.stop(0); }
    }

    @Test void missingAndAccessDeniedAreDistinctOverHttp(@TempDir Path dir) throws Exception {
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            boolean missing = exchange.getRequestURI().getPath().endsWith("missing");
            byte[] body = (missing ? "<Error><Code>NoSuchKey</Code></Error>" : "<Error><Code>AccessDenied</Code></Error>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(missing ? 404 : 403, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort())) {
            assertEquals(StorageFailure.NOT_FOUND,
                    assertThrows(StorageException.class, () -> adapter.read("missing")).getReason());
            assertEquals(StorageFailure.STORAGE_ERROR,
                    assertThrows(StorageException.class, () -> adapter.read("denied")).getReason());
            Path file = Files.write(dir.resolve("upload"), DATA);
            assertEquals(StorageFailure.STORAGE_ERROR, assertThrows(StorageException.class,
                    () -> adapter.put("denied", file, DATA.length, MediaType.PNG, checksum())).getReason());
        } finally { server.stop(0); }
    }

    @Test void interruptedReadDoesNotReturnACompleteFile() throws Exception {
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, DATA.length * 2);
            try (var output = exchange.getResponseBody()) { output.write(DATA); }
            catch (IOException expected) { exchange.close(); }
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort()); var object = adapter.read("key")) {
            assertEquals(DATA.length * 2, object.size());
            assertThrows(IOException.class, () -> object.input().readAllBytes());
        } finally { server.stop(0); }
    }

    @Test void unavailableEndpointDoesNotReportReadOrWriteSuccess(@TempDir Path dir) throws Exception {
        int port;
        try (var reserved = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reserved.getLocalPort();
        }
        try (var adapter = storage(port)) {
            Path file = Files.write(dir.resolve("upload"), DATA);
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, assertThrows(StorageException.class,
                    () -> adapter.put("key", file, DATA.length, MediaType.PNG, checksum())).getReason());
            assertEquals(StorageFailure.STORAGE_ERROR,
                    assertThrows(StorageException.class, () -> adapter.read("key")).getReason());
        }
    }

    @Test void responseTimeoutAfterBodyIsUnknown(@TempDir Path dir) throws Exception {
        var received = new AtomicReference<byte[]>();
        var requests = new AtomicInteger();
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            received.set(exchange.getRequestBody().readAllBytes());
            try { Thread.sleep(2000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort(), Duration.ofMillis(800))) {
            Path file = Files.write(dir.resolve("upload"), DATA);
            var error = assertThrows(StorageException.class,
                    () -> adapter.put("key", file, DATA.length, MediaType.PNG, checksum()));
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
            Throwable cause = error;
            while (cause != null && !(cause instanceof ApiCallTimeoutException)) cause = cause.getCause();
            assertNotNull(cause, () -> "the SDK must report the configured API call timeout: " + error.getCause());
            assertNotNull(received.get(), "timeout must happen after request body receipt");
            assertArrayEquals(DATA, decodeAwsChunks(received.get()));
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test void interruptedPutAfterPartialBodyHasUnknownOutcome(@TempDir Path dir) throws Exception {
        byte[] data = new byte[5_000_000];
        java.util.Arrays.fill(data, (byte) 42);
        Path file = Files.write(dir.resolve("large-upload"), data);
        String digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data));
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var received = CompletableFuture.supplyAsync(() -> {
                try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var input = socket.getInputStream();
                    var headers = new ByteArrayOutputStream();
                    int match = 0;
                    byte[] end = {'\r', '\n', '\r', '\n'};
                    while (match < end.length) {
                        int next = input.read();
                        if (next < 0 || headers.size() > 16_384) throw new IOException("incomplete headers");
                        headers.write(next);
                        match = next == end[match] ? match + 1 : (next == end[0] ? 1 : 0);
                    }
                    if (headers.toString(StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT)
                            .contains("expect: 100-continue")) {
                        socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().flush();
                    }
                    int count = input.readNBytes(512).length;
                    socket.setSoLinger(true, 0);
                    return count;
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            try (var adapter = storage(server.getLocalPort())) {
                var error = assertThrows(StorageException.class,
                        () -> adapter.put("key", file, data.length, MediaType.PNG, digest));
                assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
                assertEquals(512, received.get(5, TimeUnit.SECONDS), "the connection must close during the request body");
            }
        }
    }

    @Test void socketFailureRemovesServiceTemporaryFile() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
        var temporary = new AtomicReference<Path>();
        var body = new AtomicReference<byte[]>();
        HttpServer server = localServer();
        server.createContext("/", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.close();
        });
        server.start();
        try (var adapter = storage(server.getAddress().getPort())) {
            MediaObjectStorage tracked = new MediaObjectStorage() {
                @Override public com.louiekury.media.storage.application.model.ObjectObservation observe(String key) { return adapter.observe(key); }
                @Override public void put(String key, Path file, long size, MediaType type, String checksum) {
                    temporary.set(file);
                    adapter.put(key, file, size, type, checksum);
                }
                @Override public StoredObject read(String key) { return adapter.read(key); }
            };
            var service = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), tracked);
            var error = assertThrows(StorageException.class, () -> service.store("key", MediaUsage.POST_ATTACHMENT,
                    "photo.png", "image/png", new ByteArrayInputStream(png)));
            assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
            assertArrayEquals(png, decodeAwsChunks(body.get()));
            assertNotNull(temporary.get());
            assertFalse(Files.exists(temporary.get()), "socket failure must not leave the service temporary file");
        } finally { server.stop(0); }
    }
}
