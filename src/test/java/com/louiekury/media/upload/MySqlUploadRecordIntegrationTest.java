package com.louiekury.media.upload;

import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.MediaUploadService;
import com.louiekury.media.storage.application.MediaStorageService;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 전용 빈 로컬 MySQL DB에만 실행: MEDIA_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:3306/media_test_이름,
 * MEDIA_TEST_MYSQL_USER, MEDIA_TEST_MYSQL_PASSWORD를 지정한다. 스키마와 테스트 행은 남는다.
 * DB 서버 재시작은 이 테스트가 수행하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = ".+")
class MySqlUploadRecordIntegrationTest {
    private static final ValidatedMedia PNG = new ValidatedMedia(MediaType.PNG, 123);

    static DriverManagerDataSource dataSource() {
        String url = System.getenv("MEDIA_TEST_MYSQL_URL");
        if (!url.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]{1,5}/media_test_[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("로컬 전용 media_test_ 데이터베이스 URL이 필요합니다");
        }
        String user = System.getenv("MEDIA_TEST_MYSQL_USER");
        String password = System.getenv("MEDIA_TEST_MYSQL_PASSWORD");
        if (user == null || user.isBlank() || password == null) {
            throw new IllegalArgumentException("전용 MySQL 사용자와 비밀번호를 지정하세요");
        }
        return new DriverManagerDataSource(url, user, password);
    }

    @Test void migrationConstraintsRollbackAndConcurrentCompletion() throws Exception {
        var dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var store = new JdbcUploadRecordStore(jdbc, new DataSourceTransactionManager(dataSource));
        UUID uploader = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        store.prepare(job, key, uploader, MediaUsage.POST_ATTACHMENT);
        assertThrows(DataIntegrityViolationException.class,
                () -> store.prepare(UUID.randomUUID(), key, uploader, MediaUsage.POST_ATTACHMENT));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO temporary_attachment (media_id, usage_name, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                Long.MAX_VALUE, MediaUsage.POST_ATTACHMENT.name()));
        store.markStored(job);
        store.complete(job, media, "photo.png", PNG);
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM media WHERE uuid = ?", media.toString()));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON t.media_id = m.id WHERE m.uuid = ?", media.toString()));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO temporary_attachment (media_id, usage_name, created_at) SELECT id, ?, CURRENT_TIMESTAMP FROM media WHERE uuid = ?",
                MediaUsage.POST_ATTACHMENT.name(), media.toString()));

        UUID failed = UUID.randomUUID();
        String failedKey = UUID.randomUUID().toString();
        store.prepare(failed, failedKey, uploader, MediaUsage.POST_ATTACHMENT);
        store.markStored(failed);
        assertThrows(DataIntegrityViolationException.class, () -> store.complete(failed, media, "photo.png", PNG));
        assertEquals(UploadStatus.STORED, store.find(failed).orElseThrow().status());
        assertEquals(0, count(jdbc, "SELECT COUNT(*) FROM media WHERE object_key = ?", failedKey));

        UUID raced = UUID.randomUUID();
        String racedKey = UUID.randomUUID().toString();
        store.prepare(raced, racedKey, uploader, MediaUsage.POST_ATTACHMENT);
        store.markStored(raced);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> completeAfter(start, store, raced));
            var second = executor.submit(() -> completeAfter(start, store, raced));
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM media WHERE object_key = ?", racedKey));
        assertEquals(1, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON t.media_id = m.id WHERE m.object_key = ?", racedKey));

        var reconnectedDataSource = dataSource();
        var reconnected = new JdbcUploadRecordStore(new JdbcTemplate(reconnectedDataSource),
                new DataSourceTransactionManager(reconnectedDataSource));
        assertEquals(UploadStatus.COMPLETED, reconnected.find(job).orElseThrow().status());
        assertEquals(UploadStatus.STORED, reconnected.find(failed).orElseThrow().status());
    }

    private static int count(JdbcTemplate jdbc, String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    @Test void completionCommitFailureBeforeOrAfterCommitKeepsTraceableJob() {
        var dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        for (boolean commitApplied : new boolean[]{false, true}) {
            var manager = new CompletionCommitFailure(new DataSourceTransactionManager(dataSource), commitApplied);
            var records = new JdbcUploadRecordStore(jdbc, manager);
            var storage = mock(MediaStorageService.class);
            when(storage.store(anyString(), any(), anyString(), anyString(), any(), any())).thenReturn(PNG);
            var service = new MediaUploadService(storage, records);
            UUID job = UUID.randomUUID();
            UUID uploader = UUID.randomUUID();
            UUID result = null;
            if (commitApplied) {
                result = service.upload(job, uploader, MediaUsage.POST_ATTACHMENT, "image.png", "image/png", new ByteArrayInputStream(new byte[]{1}));
            } else {
                assertThrows(TransactionSystemException.class, () -> service.upload(job, uploader,
                        MediaUsage.POST_ATTACHMENT, "image.png", "image/png", new ByteArrayInputStream(new byte[]{1})));
            }
            var persisted = records.find(job).orElseThrow();
            if (commitApplied) assertEquals(persisted.mediaId(), result);
            assertNotNull(persisted.objectKey());
            assertEquals(commitApplied ? UploadStatus.COMPLETED : UploadStatus.STORED, persisted.status());
            assertEquals(commitApplied ? 1 : 0, count(jdbc, "SELECT COUNT(*) FROM media WHERE object_key = ?", persisted.objectKey()));
            assertEquals(commitApplied ? 1 : 0, count(jdbc, "SELECT COUNT(*) FROM temporary_attachment t JOIN media m ON t.media_id = m.id WHERE m.object_key = ?", persisted.objectKey()));
            verify(storage, times(1)).store(eq(persisted.objectKey()), any(), anyString(), anyString(), any(), any());
        }
    }

    /** 주입은 세 번째 트랜잭션의 커밋 직전 롤백 또는 성공 직후 예외다. 실제 네트워크 유실 재현은 아니다. */
    private static final class CompletionCommitFailure implements PlatformTransactionManager {
        private final PlatformTransactionManager delegate;
        private final boolean commitApplied;
        private int commits;
        CompletionCommitFailure(PlatformTransactionManager delegate, boolean commitApplied) {
            this.delegate = delegate;
            this.commitApplied = commitApplied;
        }
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return delegate.getTransaction(definition);
        }
        @Override public void commit(TransactionStatus status) {
            if (++commits != 3) {
                delegate.commit(status);
                return;
            }
            if (commitApplied) delegate.commit(status);
            else delegate.rollback(status);
            throw new TransactionSystemException("injected completion commit failure");
        }
        @Override public void rollback(TransactionStatus status) { delegate.rollback(status); }
    }

    private static boolean completeAfter(CountDownLatch start, JdbcUploadRecordStore store, UUID job) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            store.complete(job, UUID.randomUUID(), "photo.png", PNG);
            return true;
        } catch (IllegalStateException expected) {
            return false;
        }
    }
}
