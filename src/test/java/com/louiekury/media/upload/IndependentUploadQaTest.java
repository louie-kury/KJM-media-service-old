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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 독립 QA: 전용 로컬 MySQL과 S3Mock이 필요하며 DB에 테스트 행을 남긴다. */
@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = ".+")
class IndependentUploadQaTest {
    private static final String BUCKET = "codex-media-test";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final MediaUsage USAGE = MediaUsage.POST_ATTACHMENT;

    @Test void independentScenariosAgainstIsolatedMysqlAndS3Mock() {
        String endpointText = System.getenv("MEDIA_TEST_S3_ENDPOINT");
        assumeTrue(endpointText != null && !endpointText.isBlank(), "로컬 S3Mock 주소가 필요합니다");
        URI endpoint = URI.create(endpointText);
        if (!"http".equals(endpoint.getScheme()) ||
                !("localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost())) ||
                endpoint.getPort() < 1 || endpoint.getUserInfo() != null || endpoint.getQuery() != null ||
                endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException("로컬 S3Mock HTTP 주소만 허용합니다");
        }
        String dbUrl = System.getenv("MEDIA_TEST_MYSQL_URL");
        if (!dbUrl.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}/media_test_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("로컬 전용 media_test_ 데이터베이스가 필요합니다");
        }
        String user = System.getenv("MEDIA_TEST_MYSQL_USER");
        String password = System.getenv("MEDIA_TEST_MYSQL_PASSWORD");
        if (user == null || user.isBlank() || password == null) throw new IllegalArgumentException("전용 DB 계정이 필요합니다");
        var dataSource = new DriverManagerDataSource(dbUrl, user, password);
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var tx = new DataSourceTransactionManager(dataSource);
        var records = new JdbcUploadRecordStore(jdbc, tx);
        UUID uploader = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID invalidJob = UUID.randomUUID();
        UUID failedCommitJob = UUID.randomUUID();
        String savedKey = null;
        String failedCommitKey = null;
        try (S3Client inspect = builder(endpoint).build();
             var storage = new S3MediaObjectStorage(builder(endpoint), BUCKET)) {
            try {
                var fileStorage = new MediaStorageService(new MediaValidationService(new FileSignatureDetector()), storage);
                var service = new MediaUploadService(fileStorage, records);
                UUID media = service.upload(job, uploader, USAGE, "qa.png", "image/png", new ByteArrayInputStream(PNG));
                assertNotNull(media);
                var saved = records.find(job).orElseThrow();
                savedKey = saved.objectKey();
                assertEquals(UploadStatus.COMPLETED, saved.status());
                assertEquals(media, saved.mediaId());
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM media WHERE uuid = ?", media.toString()));
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON m.id = t.media_id WHERE m.uuid = ?", media.toString()));
                assertEquals("image/png", jdbc.queryForObject("SELECT content_type FROM media WHERE uuid = ?", String.class, media.toString()));
                assertEquals(Long.valueOf(PNG.length), jdbc.queryForObject("SELECT file_size FROM media WHERE uuid = ?", Long.class, media.toString()));
                var object = inspect.getObjectAsBytes(r -> r.bucket(BUCKET).key(saved.objectKey()));
                assertArrayEquals(PNG, object.asByteArray());
                assertEquals("image/png", object.response().contentType());
                assertEquals(Long.valueOf(PNG.length), object.response().contentLength());
                System.out.println("정상 업로드: 반환 UUID, DB 미디어·첨부 각 1, S3 바이트·형식·크기 일치");

                assertThrows(MediaValidationException.class, () -> service.upload(invalidJob, uploader, USAGE,
                        "bad.png", "image/png", new ByteArrayInputStream(new byte[]{1, 2, 3})));
                var rejected = records.find(invalidJob).orElseThrow();
                assertEquals(UploadStatus.FAILED, rejected.status());
                assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM media WHERE object_key = ?", rejected.objectKey()));
                assertThrows(NoSuchKeyException.class, () -> inspect.getObjectAsBytes(r -> r.bucket(BUCKET).key(rejected.objectKey())));
                System.out.println("잘못된 파일: 실패 작업 보존, 완료 미디어와 S3 객체 없음");

                Set<String> before = keys(inspect);
                assertThrows(DataIntegrityViolationException.class, () -> service.upload(job, uploader, USAGE,
                        "qa.png", "image/png", new ByteArrayInputStream(PNG)));
                assertEquals(before, keys(inspect));
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM media WHERE uuid = ?", media.toString()));
                assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON m.id = t.media_id WHERE m.uuid = ?", media.toString()));
                System.out.println("동일 작업 재호출: 거절, 추가 DB 기록과 S3 객체 없음");

                JdbcTemplate injected = new JdbcTemplate(dataSource) {
                    @Override public int update(String sql, Object... args) {
                        if (sql.startsWith("INSERT INTO temporary_attachment")) throw new DataIntegrityViolationException("QA injected attachment failure");
                        return super.update(sql, args);
                    }
                };
                var failingRecords = new JdbcUploadRecordStore(injected, tx);
                var failingService = new MediaUploadService(fileStorage, failingRecords);
                assertThrows(DataIntegrityViolationException.class, () -> failingService.upload(failedCommitJob, uploader, USAGE,
                        "qa.png", "image/png", new ByteArrayInputStream(PNG)));
                var pending = records.find(failedCommitJob).orElseThrow();
                failedCommitKey = pending.objectKey();
                assertEquals(UploadStatus.STORED, pending.status());
                assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM media WHERE object_key = ?", pending.objectKey()));
                assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON m.id = t.media_id WHERE m.object_key = ?", pending.objectKey()));
                assertArrayEquals(PNG, inspect.getObjectAsBytes(r -> r.bucket(BUCKET).key(pending.objectKey())).asByteArray());
                System.out.println("첨부 insert 실패 주입: 미디어·첨부 롤백, STORED 작업과 S3 키 보존");
                System.out.println("저장 응답 유실: 프록시 미구성으로 미검증. DB 테스트 행은 남습니다.");
            } finally {
                if (savedKey != null) delete(inspect, savedKey);
                if (failedCommitKey != null) delete(inspect, failedCommitKey);
            }
        }
    }

    private static S3ClientBuilder builder(URI endpoint) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }

    private static Set<String> keys(S3Client client) {
        return client.listObjectsV2Paginator(r -> r.bucket(BUCKET)).contents().stream().map(o -> o.key()).collect(Collectors.toSet());
    }

    private static int count(JdbcTemplate jdbc, String sql, String value) {
        return jdbc.queryForObject(sql, Integer.class, value);
    }

    private static void delete(S3Client client, String key) {
        client.deleteObject(r -> r.bucket(BUCKET).key(key));
        System.out.println("확인된 테스트 S3 객체 삭제 완료");
    }
}
