package com.louiekury.media.upload;

import com.louiekury.media.upload.adapter.out.JdbcUploadRecordStore;
import com.louiekury.media.upload.application.model.PendingUploadCursor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PendingUploadQueryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcUploadRecordStore store = new JdbcUploadRecordStore(jdbc, mock(DataSourceTransactionManager.class));
    private final Instant cutoff = Instant.parse("2020-01-01T00:00:00Z");

    @Test void rejectsInvalidInputsBeforeQuerying() {
        assertThrows(RuntimeException.class, () -> store.findPending(null, null, 1));
        assertThrows(RuntimeException.class, () -> store.findPending(cutoff, null, 0));
        assertThrows(RuntimeException.class, () -> store.findPending(cutoff, null, -1));
        assertThrows(RuntimeException.class, () -> store.findPending(cutoff, null, 101));
        assertThrows(RuntimeException.class, () -> store.findPending(cutoff, new PendingUploadCursor(null, UUID.randomUUID()), 1));
        assertThrows(RuntimeException.class, () -> store.findPending(cutoff, new PendingUploadCursor(cutoff, null), 1));
        verifyNoInteractions(jdbc);
    }

    @Test void databaseErrorsAreNotReportedAsEmptyResults() {
        var ds = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/media_test_unreachable", "unused", "unused");
        var unreachable = new JdbcUploadRecordStore(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> unreachable.findPending(cutoff, null, 1));
    }
}
