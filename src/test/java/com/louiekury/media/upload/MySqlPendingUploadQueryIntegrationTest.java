package com.louiekury.media.upload;

import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.model.PendingUploadCursor;
import com.louiekury.media.upload.application.model.PendingUploadJob;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MEDIA_TEST_MYSQL_URL", matches = ".+")
class MySqlPendingUploadQueryIntegrationTest {
    @Test void utcTimestampsAndCursorsWorkWithNonUtcSession() throws Exception {
        var initial = MySqlUploadRecordIntegrationTest.dataSource();
        Flyway.configure().dataSource(initial).load().migrate();
        try (var connection = initial.getConnection()) {
            try (var statement = connection.createStatement()) {
                statement.execute("SET time_zone = '+09:00'");
            }
            var ds = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(ds);
            var store = new JdbcUploadRecordStore(jdbc, new DataSourceTransactionManager(ds));
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            try {
                Instant before = jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class).toInstant(ZoneOffset.UTC);
                store.prepare(first, first.toString(), UUID.randomUUID(), com.louiekury.media.validation.domain.MediaUsage.POST_ATTACHMENT);
                store.prepare(second, second.toString(), UUID.randomUUID(), com.louiekury.media.validation.domain.MediaUsage.POST_ATTACHMENT);
                store.markUnknown(second);
                Instant after = jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class).toInstant(ZoneOffset.UTC);
                Instant firstTime = jdbc.queryForObject("SELECT updated_at FROM upload_job WHERE id = ?", LocalDateTime.class, first.toString()).toInstant(ZoneOffset.UTC);
                Instant secondTime = jdbc.queryForObject("SELECT updated_at FROM upload_job WHERE id = ?", LocalDateTime.class, second.toString()).toInstant(ZoneOffset.UTC);
                assertFalse(firstTime.isBefore(before));
                assertFalse(firstTime.isAfter(after));
                assertFalse(secondTime.isBefore(before));
                assertFalse(secondTime.isAfter(after));

                var cursor = new PendingUploadCursor(before.minusSeconds(1), new UUID(0, 0));
                var found = new ArrayList<PendingUploadJob>();
                while (true) {
                    var page = store.findPending(after, cursor, 1);
                    if (page.isEmpty()) break;
                    var item = page.get(0);
                    if (item.job().id().equals(first) || item.job().id().equals(second)) found.add(item);
                    cursor = new PendingUploadCursor(item.updatedAt(), item.job().id());
                }
                assertEquals(2, found.size());
                assertEquals(firstTime, found.stream().filter(x -> x.job().id().equals(first)).findFirst().orElseThrow().updatedAt());
                assertEquals(secondTime, found.stream().filter(x -> x.job().id().equals(second)).findFirst().orElseThrow().updatedAt());
                assertTrue(store.findPending(before.minusSeconds(1), null, 100).stream()
                        .noneMatch(x -> x.job().id().equals(first) || x.job().id().equals(second)));
            } finally {
                jdbc.update("DELETE FROM upload_job WHERE id IN (?, ?)", first.toString(), second.toString());
            }
        }
    }

    @Test void filtersAndPaginatesWithoutChangingJobs() {
        var ds = MySqlUploadRecordIntegrationTest.dataSource();
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var store = new JdbcUploadRecordStore(jdbc, new DataSourceTransactionManager(ds));
        Instant cutoff = Instant.parse("1971-01-02T00:00:00Z");
        Instant earlier = cutoff.minusSeconds(60);
        List<UUID> ids = new ArrayList<>();
        try {
            for (String state : List.of("PREPARED", "OUTCOME_UNKNOWN", "STORED", "FAILED", "COMPLETED")) {
                UUID id = UUID.randomUUID(); ids.add(id);
                insert(jdbc, id, state, cutoff);
            }
            UUID early = UUID.randomUUID(); ids.add(early); insert(jdbc, early, "PREPARED", earlier);
            UUID later = UUID.randomUUID(); ids.add(later); insert(jdbc, later, "STORED", cutoff.plusSeconds(1));
            assertEquals(LocalDateTime.ofInstant(earlier, ZoneOffset.UTC), jdbc.queryForObject(
                    "SELECT updated_at FROM upload_job WHERE id = ?", LocalDateTime.class, early.toString()));
            var expected = new ArrayList<>(ids.subList(0, 3));
            expected.sort((a, b) -> a.toString().compareTo(b.toString()));
            expected.add(0, early);

            List<PendingUploadJob> found = new ArrayList<>();
            PendingUploadCursor cursor = null;
            for (int page = 0; page < 5; page++) {
                var result = store.findPending(cutoff, cursor, 1);
                if (result.isEmpty()) break;
                assertEquals(1, result.size());
                var last = result.get(0); found.add(last);
                cursor = new PendingUploadCursor(last.updatedAt(), last.job().id());
            }
            assertEquals(expected, found.stream().map(item -> item.job().id()).toList());
            assertEquals(earlier, found.get(0).updatedAt());
            assertEquals(cutoff, found.get(1).updatedAt());
            assertEquals(expected, store.findPending(cutoff, null, 100).stream().map(item -> item.job().id()).toList());
            assertTrue(store.findPending(earlier.minusSeconds(1), null, 1).isEmpty());
            assertEquals(7, ids.stream().filter(id -> jdbc.queryForObject(
                    "SELECT COUNT(*) FROM upload_job WHERE id = ?", Integer.class, id.toString()) == 1).count());
            assertEquals("PREPARED", jdbc.queryForObject("SELECT status FROM upload_job WHERE id = ?", String.class, early.toString()));
        } finally {
            ids.forEach(id -> jdbc.update("DELETE FROM upload_job WHERE id = ?", id.toString()));
        }
    }

    private static void insert(JdbcTemplate jdbc, UUID id, String state, Instant updated) {
        jdbc.update("INSERT INTO upload_job (id, object_key, uploader_uuid, usage_name, status, created_at, updated_at) VALUES (?, ?, ?, 'POST_ATTACHMENT', ?, ?, ?)",
                id.toString(), id.toString(), UUID.randomUUID().toString(), state,
                LocalDateTime.ofInstant(updated, ZoneOffset.UTC), LocalDateTime.ofInstant(updated, ZoneOffset.UTC));
    }
}
