package com.louiekury.media.upload;

import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.domain.MediaType;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = ".+")
class MySqlRecoveryMetadataIntegrationTest {
    private static final String DIGEST = Base64.getEncoder().encodeToString(new byte[32]);
    private static final ValidatedMedia PNG = new ValidatedMedia(MediaType.PNG, 123);

    @Test void migrationPreservesOldRowsAndRecordIsAtomicWithStateAndDuplicateGuards() throws Exception {
        var base = MySqlUploadRecordIntegrationTest.dataSource();
        var admin = new JdbcTemplate(base);
        String schema = "media_test_recovery_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + schema + "`");
        try {
            String url = base.getUrl();
            var ds = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + schema,
                    base.getUsername(), base.getPassword());
            Flyway.configure().dataSource(ds).target("1").load().migrate();
            var jdbc = new JdbcTemplate(ds);
            UUID legacy = UUID.randomUUID();
            jdbc.update("INSERT INTO upload_job (id, object_key, uploader_uuid, usage_name, status, created_at, updated_at) VALUES (?, ?, ?, 'POST_ATTACHMENT', 'PREPARED', UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                    legacy.toString(), legacy.toString(), UUID.randomUUID().toString());
            Flyway.configure().dataSource(ds).load().migrate();
            var store = new JdbcUploadRecordStore(jdbc, new DataSourceTransactionManager(ds));
            assertNull(store.find(legacy).orElseThrow().recoveryMetadata());
            store.recordValidated(legacy, "가".repeat(251) + ".png", PNG, DIGEST);
            var metadata = store.find(legacy).orElseThrow().recoveryMetadata();
            assertNotNull(metadata);
            assertEquals("가".repeat(251) + ".png", metadata.filename());
            assertEquals(MediaType.PNG, metadata.type());
            assertEquals(123, metadata.size());
            assertEquals(DIGEST, metadata.sha256());
            assertThrows(RuntimeException.class, () -> store.recordValidated(legacy, "other.png", PNG, DIGEST));
            assertEquals(metadata, store.find(legacy).orElseThrow().recoveryMetadata());

            UUID changed = UUID.randomUUID();
            store.prepare(changed, changed.toString(), UUID.randomUUID(), MediaUsage.POST_ATTACHMENT);
            store.markUnknown(changed);
            assertThrows(RuntimeException.class, () -> store.recordValidated(changed, "photo.png", PNG, DIGEST));
            assertNull(store.find(changed).orElseThrow().recoveryMetadata());

            UUID invalid = UUID.randomUUID();
            store.prepare(invalid, invalid.toString(), UUID.randomUUID(), MediaUsage.POST_ATTACHMENT);
            assertThrows(IllegalArgumentException.class, () -> store.recordValidated(invalid, "", PNG, DIGEST));
            assertThrows(RuntimeException.class, () -> jdbc.update(
                    "UPDATE upload_job SET validated_filename = 'partial.png' WHERE id = ?", invalid.toString()));
            assertNull(store.find(invalid).orElseThrow().recoveryMetadata());
            var failingJdbc = new JdbcTemplate(ds) {
                @Override public int update(String sql, Object... args) {
                    int updated = super.update(sql, args);
                    if (sql.startsWith("UPDATE upload_job SET validated_filename")) {
                        throw new IllegalStateException("injected after metadata update");
                    }
                    return updated;
                }
            };
            var failingStore = new JdbcUploadRecordStore(failingJdbc, new DataSourceTransactionManager(ds));
            assertThrows(IllegalStateException.class, () -> failingStore.recordValidated(invalid, "photo.png", PNG, DIGEST));
            assertNull(store.find(invalid).orElseThrow().recoveryMetadata());

            UUID raced = UUID.randomUUID();
            store.prepare(raced, raced.toString(), UUID.randomUUID(), MediaUsage.POST_ATTACHMENT);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> recordAfter(start, store, raced, "first.png"));
                var second = pool.submit(() -> recordAfter(start, store, raced, "second.png"));
                start.countDown();
                assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
                assertNotNull(store.find(raced).orElseThrow().recoveryMetadata());
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }

            UUID transitionRace = UUID.randomUUID();
            store.prepare(transitionRace, transitionRace.toString(), UUID.randomUUID(), MediaUsage.POST_ATTACHMENT);
            var raceStart = new CountDownLatch(1);
            var racePool = Executors.newFixedThreadPool(2);
            try {
                var record = racePool.submit(() -> recordAfter(raceStart, store, transitionRace, "photo.png"));
                var transition = racePool.submit(() -> {
                    assertTrue(raceStart.await(10, TimeUnit.SECONDS));
                    store.markUnknown(transitionRace);
                    return true;
                });
                raceStart.countDown();
                boolean recorded = record.get(15, TimeUnit.SECONDS);
                assertTrue(transition.get(15, TimeUnit.SECONDS));
                var result = store.find(transitionRace).orElseThrow();
                assertEquals(UploadStatus.OUTCOME_UNKNOWN, result.status());
                if (recorded) {
                    assertNotNull(result.recoveryMetadata());
                    assertEquals("photo.png", result.recoveryMetadata().filename());
                    assertEquals(MediaType.PNG, result.recoveryMetadata().type());
                    assertEquals(123, result.recoveryMetadata().size());
                    assertEquals(DIGEST, result.recoveryMetadata().sha256());
                } else {
                    assertNull(result.recoveryMetadata());
                }
            } finally {
                raceStart.countDown();
                racePool.shutdownNow();
                assertTrue(racePool.awaitTermination(10, TimeUnit.SECONDS));
            }
        } finally {
            admin.execute("DROP DATABASE `" + schema + "`");
        }
    }

    private static boolean recordAfter(CountDownLatch start, JdbcUploadRecordStore store, UUID job, String filename)
            throws InterruptedException {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try { store.recordValidated(job, filename, PNG, DIGEST); return true; }
        catch (IllegalStateException expected) { return false; }
    }
}
