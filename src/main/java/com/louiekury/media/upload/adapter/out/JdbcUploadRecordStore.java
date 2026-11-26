package com.louiekury.media.upload.adapter.out;

import com.louiekury.media.upload.application.model.UploadJob;
import com.louiekury.media.upload.application.model.PendingUploadCursor;
import com.louiekury.media.upload.application.model.PendingUploadJob;
import com.louiekury.media.upload.application.model.UploadStatus;
import com.louiekury.media.upload.application.port.out.UploadRecordStore;
import com.louiekury.media.validation.application.model.ValidatedMedia;
import com.louiekury.media.validation.domain.MediaUsage;
import java.util.List;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcUploadRecordStore implements UploadRecordStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcUploadRecordStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void prepare(UUID jobId, String objectKey, UUID uploaderId, MediaUsage usage) {
        transaction.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO upload_job (id, object_key, uploader_uuid, usage_name, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PREPARED', UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                jobId.toString(), objectKey, uploaderId.toString(), usage.name()));
    }

    @Override public void markUnknown(UUID jobId) { transition(jobId, "OUTCOME_UNKNOWN"); }
    @Override public void markStored(UUID jobId) { transition(jobId, "STORED"); }
    @Override public void markFailed(UUID jobId) { transition(jobId, "FAILED"); }

    private void transition(UUID jobId, String next) {
        transaction.executeWithoutResult(status -> {
            int count = jdbc.update("UPDATE upload_job SET status = ?, updated_at = UTC_TIMESTAMP() "
                    + "WHERE id = ? AND status = 'PREPARED'", next, jobId.toString());
            if (count != 1) throw new IllegalStateException("Upload job is not prepared");
        });
    }

    @Override
    public void complete(UUID jobId, UUID mediaId, String filename, ValidatedMedia media) {
        transaction.executeWithoutResult(status -> {
            int count = jdbc.update("UPDATE upload_job SET status = 'COMPLETED', media_uuid = ?, "
                    + "updated_at = UTC_TIMESTAMP() WHERE id = ? AND status = 'STORED'",
                    mediaId.toString(), jobId.toString());
            if (count != 1) throw new IllegalStateException("Upload job is not stored");
            UploadJob job = find(jobId).orElseThrow();
            jdbc.update("INSERT INTO media (uuid, uploader_uuid, file_name, content_type, object_key, "
                            + "file_size, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                    mediaId.toString(), job.uploaderId().toString(), filename, media.getType().getMimeType(),
                    job.objectKey(), media.getSize());
            jdbc.update("INSERT INTO temporary_attachment (media_id, usage_name, created_at) "
                            + "SELECT id, ?, UTC_TIMESTAMP() FROM media WHERE uuid = ?",
                    job.usage().name(), mediaId.toString());
        });
    }

    @Override
    public Optional<UploadJob> find(UUID jobId) {
        List<UploadJob> jobs = jdbc.query("SELECT id, object_key, uploader_uuid, usage_name, status, media_uuid "
                        + "FROM upload_job WHERE id = ?", (rs, row) -> mapJob(rs),
                jobId.toString());
        return jobs.stream().findFirst();
    }

    @Override
    public List<PendingUploadJob> findPending(Instant cutoff, PendingUploadCursor cursor, int limit) {
        Objects.requireNonNull(cutoff, "cutoff");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        String sql = "SELECT id, object_key, uploader_uuid, usage_name, status, media_uuid, updated_at "
                + "FROM upload_job WHERE status IN ('PREPARED', 'OUTCOME_UNKNOWN', 'STORED') AND updated_at <= ? ";
        if (cursor == null) {
            return jdbc.query(sql + "ORDER BY updated_at, id LIMIT ?", (rs, row) ->
                    new PendingUploadJob(mapJob(rs), rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                    LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC), limit);
        }
        return jdbc.query(sql + "AND (updated_at > ? OR (updated_at = ? AND id > ?)) ORDER BY updated_at, id LIMIT ?",
                (rs, row) -> new PendingUploadJob(mapJob(rs), rs.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC), LocalDateTime.ofInstant(cursor.updatedAt(), ZoneOffset.UTC),
                LocalDateTime.ofInstant(cursor.updatedAt(), ZoneOffset.UTC),
                cursor.id().toString(), limit);
    }

    private static UploadJob mapJob(ResultSet rs) throws SQLException {
        String mediaId = rs.getString("media_uuid");
        return new UploadJob(UUID.fromString(rs.getString("id")), rs.getString("object_key"),
                UUID.fromString(rs.getString("uploader_uuid")), MediaUsage.valueOf(rs.getString("usage_name")),
                UploadStatus.valueOf(rs.getString("status")), mediaId == null ? null : UUID.fromString(mediaId));
    }
}
