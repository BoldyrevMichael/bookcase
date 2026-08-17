package com.bookcase.enricher.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Учёт уже обработанных событий.
 *
 * <p>Событие о появлении книги может приехать повторно — так устроена доставка «хотя бы один раз».
 * Заводить по нему вторую задачу уточнения незачем: она сожжёт ещё один запрос из суточной квоты
 * ради того же самого ответа.
 */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbc;

    public ProcessedEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean alreadyProcessed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_event WHERE event_id = :eventId")
                        .param("eventId", eventId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public void markProcessed(UUID eventId) {
        jdbc.sql("INSERT INTO processed_event (event_id) VALUES (:eventId) ON CONFLICT DO NOTHING")
                .param("eventId", eventId)
                .update();
    }
}
