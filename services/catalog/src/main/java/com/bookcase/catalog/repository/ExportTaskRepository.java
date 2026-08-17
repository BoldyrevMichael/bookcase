package com.bookcase.catalog.repository;

import com.bookcase.catalog.service.state.ExportStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Задачи выгрузки библиотеки. */
@Repository
public class ExportTaskRepository {

    private final JdbcClient jdbc;

    public ExportTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(UUID id, String ownerId, int bookCount) {
        jdbc.sql(
                        """
                        INSERT INTO export_task (id, owner_id, status, book_count)
                        VALUES (:id, :ownerId, 'QUEUED', :bookCount)
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("bookCount", bookCount)
                .update();
    }

    /**
     * Записывает готовый архив. Состояние и результат меняются одним запросом: увидеть «готово» без
     * ссылки на архив читающий не должен.
     */
    public void markSucceeded(UUID id, String archiveKey, long sizeBytes) {
        jdbc.sql(
                        """
                        UPDATE export_task
                        SET archive_key = :key, size_bytes = :size, failure_reason = NULL,
                            status = 'SUCCEEDED', updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("key", archiveKey)
                .param("size", sizeBytes)
                .update();
    }

    public void markFailed(UUID id, String reason) {
        jdbc.sql(
                        """
                        UPDATE export_task
                        SET failure_reason = :reason, status = 'FAILED', updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    public Optional<Task> find(UUID id, String ownerId) {
        return jdbc.sql("SELECT * FROM export_task WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(
                        (rs, row) ->
                                new Task(
                                        rs.getObject("id", UUID.class),
                                        ExportStatus.valueOf(rs.getString("status")),
                                        rs.getInt("book_count"),
                                        rs.getString("archive_key"),
                                        (Long) rs.getObject("size_bytes"),
                                        rs.getString("failure_reason"),
                                        rs.getTimestamp("created_at").toInstant(),
                                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /**
     * Задача выгрузки в том виде, в каком она лежит в базе.
     *
     * @param id задача
     * @param status состояние
     * @param bookCount сколько книг просили выгрузить
     * @param archiveKey имя объекта с архивом
     * @param sizeBytes размер архива
     * @param failureReason причина отказа
     * @param createdAt когда заведена
     * @param updatedAt когда менялась
     */
    public record Task(
            UUID id,
            ExportStatus status,
            int bookCount,
            String archiveKey,
            Long sizeBytes,
            String failureReason,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}
}
