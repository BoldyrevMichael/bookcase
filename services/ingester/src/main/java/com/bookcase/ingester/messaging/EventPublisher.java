package com.bookcase.ingester.messaging;

import com.bookcase.events.BookIngestionFailed;
import com.bookcase.events.BookIngestionRequested;
import com.bookcase.events.BookMetadataExtracted;
import com.bookcase.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Отправка событий.
 *
 * <p>Ключом служит задача: события одной задачи попадают в одну партицию и приходят потребителю в
 * том порядке, в каком отправлены. Раскладывать по владельцу было бы хуже — при разовой загрузке
 * большой библиотеки вся работа досталась бы одной партиции.
 */
@Component
public class EventPublisher {

    // Готовый отправитель, собранный по настройкам: ключ строковый, значение — событие.
    // Обобщённые типы у него <Object, Object>, потому что таким его создаёт автонастройка,
    // а заводить рядом второй такой же значило бы сделать выбор между ними неоднозначным —
    // в том числе для механизма повторов, который тоже отправляет сообщения.
    private final KafkaTemplate<Object, Object> kafka;

    public EventPublisher(KafkaTemplate<Object, Object> kafka) {
        this.kafka = kafka;
    }

    public void ingestionRequested(BookIngestionRequested event) {
        kafka.send(Topics.BOOK_INGESTION_REQUESTED, event.taskId().toString(), event);
    }

    public void metadataExtracted(BookMetadataExtracted event) {
        kafka.send(Topics.BOOK_METADATA_EXTRACTED, event.taskId().toString(), event);
    }

    public void ingestionFailed(BookIngestionFailed event) {
        kafka.send(Topics.BOOK_INGESTION_FAILED, event.taskId().toString(), event);
    }
}
