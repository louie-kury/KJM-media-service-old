package com.louiekury.media.upload;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.storage.application.StorageException;
import com.louiekury.media.storage.application.StorageFailure;
import com.louiekury.media.storage.application.model.StoredObject;
import com.louiekury.media.storage.application.port.out.MediaObjectStorage;
import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.MediaUploadService;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import com.louiekury.media.validation.domain.MediaType;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 로컬 전용 MySQL 설정과 MEDIA_TEST_S3_ENDPOINT가 모두 필요하다. DB 테스트 행은 남는다. */
@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = ".+")
class MediaUploadInternalIntegrationTest {
    private static final String BUCKET = "codex-media-test";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};

    private static S3ClientBuilder localBuilder(String endpointValue) {
        URI endpoint = URI.create(endpointValue);
        if (!"http".equals(endpoint.getScheme()) ||
                !("localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost())) ||
                endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null ||
                endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("명시적인 로컬 S3Mock HTTP 주소가 필요합니다");
        }
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }

    @Test void uploadRecordsValidatedObjectAndRejectsInvalidInput() {
        String endpoint = System.getenv("MEDIA_TEST_S3_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "로컬 S3Mock 주소가 필요합니다");
        S3ClientBuilder builder = localBuilder(endpoint);
        var dataSource = MySqlUploadRecordIntegrationTest.dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var records = new JdbcUploadRecordStore(jdbc, new DataSourceTransactionManager(dataSource));
        UUID job = UUID.randomUUID();
        UUID invalidJob = UUID.randomUUID();
        UUID uploader = UUID.randomUUID();
        try (S3Client cleanup = localBuilder(endpoint).build();
             var objectStorage = new S3MediaObjectStorage(builder, BUCKET)) {
            String ownedKey = null;
            try {
                var storage = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), objectStorage);
                var service = new MediaUploadService(storage, records);
                UUID media = service.upload(job, uploader, MediaUsage.POST_ATTACHMENT, "photo.png", "image/png", new ByteArrayInputStream(PNG));
                var saved = records.find(job).orElseThrow();
                ownedKey = saved.objectKey();
                assertEquals(UploadStatus.COMPLETED, saved.status());
                assertEquals(media, saved.mediaId());
                assertEquals(PNG.length, jdbc.queryForObject("SELECT file_size FROM media WHERE uuid = ?", Long.class, media.toString()));
                assertEquals("image/png", jdbc.queryForObject("SELECT content_type FROM media WHERE uuid = ?", String.class, media.toString()));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON t.media_id = m.id WHERE m.uuid = ?", Integer.class, media.toString()));
                byte[] actual = cleanup.getObjectAsBytes(request -> request.bucket(BUCKET).key(saved.objectKey())).asByteArray();
                assertArrayEquals(PNG, actual);

                assertThrows(MediaValidationException.class, () -> service.upload(invalidJob, uploader,
                        MediaUsage.POST_ATTACHMENT, "invalid.png", "image/png", new ByteArrayInputStream(new byte[]{1, 2, 3})));
                var rejected = records.find(invalidJob).orElseThrow();
                assertEquals(UploadStatus.FAILED, rejected.status());
                assertNull(rejected.mediaId());
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM media WHERE object_key = ?", Integer.class, rejected.objectKey()));
                assertThrows(NoSuchKeyException.class, () -> cleanup.getObjectAsBytes(
                        request -> request.bucket(BUCKET).key(rejected.objectKey())));
            } finally {
                if (ownedKey != null) {
                    String key = ownedKey;
                    cleanup.deleteObject(request -> request.bucket(BUCKET).key(key));
                }
            }
        }
    }

    @Test void metadataIsCommittedBeforePutAndSurvivesLostResponse() throws Exception {
        String endpoint = System.getenv("MEDIA_TEST_S3_ENDPOINT");
        assumeTrue(endpoint != null && !endpoint.isBlank(), "로컬 S3Mock 주소가 필요합니다");
        var dataSource = MySqlUploadRecordIntegrationTest.dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        var records = new JdbcUploadRecordStore(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        UUID job = UUID.randomUUID();
        String expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(PNG));
        try (S3Client cleanup = localBuilder(endpoint).build();
             var remote = new S3MediaObjectStorage(localBuilder(endpoint), BUCKET)) {
            MediaObjectStorage interrupted = new MediaObjectStorage() {
                @Override public void put(String key, Path file, long size, MediaType type, String checksum) {
                    var saved = records.find(job).orElseThrow().recoveryMetadata();
                    assertNotNull(saved, "메타데이터는 S3 요청 전에 다른 연결에서도 조회되어야 합니다");
                    assertEquals("photo.png", saved.filename());
                    assertEquals(size, saved.size());
                    assertEquals(type, saved.type());
                    assertEquals(expected, saved.sha256());
                    assertEquals(expected, checksum);
                    remote.put(key, file, size, type, checksum);
                    throw new StorageException(StorageFailure.OUTCOME_UNKNOWN);
                }
                @Override public StoredObject read(String key) { return remote.read(key); }
            };
            var service = new MediaUploadService(new MediaStorageService(
                    new MediaValidationService(new FileSignatureDetector()), interrupted), records);
            try {
                var error = assertThrows(StorageException.class, () -> service.upload(job, UUID.randomUUID(),
                        MediaUsage.POST_ATTACHMENT, "photo.png", "image/png", new ByteArrayInputStream(PNG)));
                assertEquals(StorageFailure.OUTCOME_UNKNOWN, error.getReason());
                var saved = records.find(job).orElseThrow();
                assertEquals(UploadStatus.OUTCOME_UNKNOWN, saved.status());
                assertNull(saved.mediaId());
                assertEquals(expected, saved.recoveryMetadata().sha256());
                assertArrayEquals(PNG, cleanup.getObjectAsBytes(request -> request.bucket(BUCKET).key(saved.objectKey())).asByteArray());
            } finally {
                records.find(job).ifPresent(saved -> cleanup.deleteObject(request -> request.bucket(BUCKET).key(saved.objectKey())));
            }
        }
    }
}
