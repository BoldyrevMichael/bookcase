package com.bookcase.enricher.messaging;

import com.bookcase.events.BookEnriched;
import com.bookcase.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Отправка находок. Ключ — книга: её события приходят каталогу по порядку. */
@Component
public class EventPublisher {

    private final KafkaTemplate<Object, Object> kafka;

    public EventPublisher(KafkaTemplate<Object, Object> kafka) {
        this.kafka = kafka;
    }

    /** Справочник рассказал о книге то, чего не было в файле. Применяет находку каталог. */
    public void bookEnriched(BookEnriched event) {
        kafka.send(Topics.BOOK_ENRICHED, event.bookId().toString(), event);
    }
}
