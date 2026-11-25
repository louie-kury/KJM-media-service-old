package com.louiekury.media.upload;

import com.louiekury.media.storage.adapter.out.S3MediaObjectStorage;
import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.MediaUploadService;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.validation.adapter.out.FileSignatureDetector;
import com.louiekury.media.validation.application.service.MediaValidationException;
import com.louiekury.media.validation.application.service.MediaValidationService;
import com.louiekury.media.validation.domain.MediaUsage;
import java.io.ByteArrayInputStream;
import java.net.URI;
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
}
