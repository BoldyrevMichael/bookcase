package com.bookcase.catalog.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Учёт уже обработанных событий.
 *
 * <p>Доставка устроена так, что событие может приехать повторно. Завести вторую карточку на ту же
 * книгу мешает и уникальность пары «владелец и файл», но пометка обработанного избавляет от лишней
 * работы и делает намерение явным.
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
