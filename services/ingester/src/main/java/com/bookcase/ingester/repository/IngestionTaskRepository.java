package com.bookcase.ingester.repository;

import com.bookcase.events.BookMetadata;
import com.bookcase.ingester.dto.IngestionTaskView;
import com.bookcase.ingester.service.state.IngestionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Задачи разбора. */
@Repository
public class IngestionTaskRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public IngestionTaskRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void create(
            UUID id, String ownerId, String sha256, String originalName, String downloadTicket) {
        jdbc.sql(
                        """
                        INSERT INTO ingestion_task
                            (id, owner_id, sha256, original_name, status, download_ticket)
                        VALUES (:id, :ownerId, :sha256, :originalName, 'QUEUED', :ticket)
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("sha256", sha256)
                .param("originalName", originalName)
                .param("ticket", downloadTicket)
                .update();
    }

    public void markRunning(UUID id) {
        jdbc.sql(
                        """
                        UPDATE ingestion_task
                        SET status = 'RUNNING', attempts = attempts + 1, updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    /**
     * Записывает итог разбора.
     *
     * <p>Состояние и результат меняются одним запросом. Порядок здесь не праздный: состояние
     * «готово» не должно оказаться видно раньше, чем то, ради чего задача заводилась, — иначе
     * читающий увидит успех без метаданных или отказ без причины.
     */
    public void markSucceeded(UUID id, BookMetadata metadata) {
        jdbc.sql(
                        """
                        UPDATE ingestion_task
                        SET metadata = cast(:metadata as jsonb), failure_reason = NULL,
                            status = 'SUCCEEDED', updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("metadata", write(metadata))
                .update();
    }

    public void markFailed(UUID id, String reason) {
        jdbc.sql(
                        """
                        UPDATE ingestion_task
                        SET failure_reason = :reason, status = 'FAILED', updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    public Optional<IngestionTaskView> find(UUID id, String ownerId) {
        return jdbc.sql("SELECT * FROM ingestion_task WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(this::mapTask)
                .optional();
    }

    public List<IngestionTaskView> findAll(String ownerId, int limit) {
        return jdbc.sql(
                        """
                        SELECT * FROM ingestion_task
                        WHERE owner_id = :ownerId
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(this::mapTask)
                .list();
    }

    /** Пропуск на скачивание нужен только разбору и наружу не выходит. */
    public Optional<String> findDownloadTicket(UUID id) {
        return jdbc.sql("SELECT download_ticket FROM ingestion_task WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    private IngestionTaskView mapTask(ResultSet rs, int rowNumber) throws SQLException {
        return new IngestionTaskView(
                rs.getObject("id", UUID.class),
                rs.getString("sha256"),
                rs.getString("original_name"),
                IngestionStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                read(rs.getString("metadata")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private String write(BookMetadata metadata) {
        try {
            return json.writeValueAsString(metadata);
        } catch (JacksonException exception) {
            throw new IllegalStateException("не удалось записать метаданные", exception);
        }
    }

    private BookMetadata read(String metadata) {
        if (metadata == null) {
            return null;
        }
        try {
            return json.readValue(metadata, BookMetadata.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("не удалось прочитать метаданные", exception);
        }
    }
}
