package com.bookcase.ingester.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Учёт уже обработанных событий.
 *
 * <p>Доставка в очереди устроена так, что одно и то же событие может приехать повторно — например,
 * когда потребитель успел сделать работу, но не успел сдвинуть смещение. Разбирать книгу второй раз
 * не страшно, но заводить вторую карточку — уже да, поэтому событие помечается обработанным ровно
 * тогда, когда работа доведена до конца. Неудачная попытка следа не оставляет: её и надо повторить.
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
