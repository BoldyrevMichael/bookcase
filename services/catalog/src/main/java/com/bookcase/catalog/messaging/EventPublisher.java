package com.bookcase.catalog.messaging;

import com.bookcase.events.BookAdded;
import com.bookcase.events.BookDeleted;
import com.bookcase.events.BookEnrichmentRequested;
import com.bookcase.events.ExportRequested;
import com.bookcase.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Отправка событий. Ключ — книга или задача: их события приходят потребителю по порядку. */
@Component
public class EventPublisher {

    private final KafkaTemplate<Object, Object> kafka;

    public EventPublisher(KafkaTemplate<Object, Object> kafka) {
        this.kafka = kafka;
    }

    /** Книга появилась в библиотеке. Это событие ждёт уточнение метаданных. */
    public void bookAdded(BookAdded event) {
        kafka.send(Topics.BOOK_ADDED, event.bookId().toString(), event);
    }

    /**
     * Книги больше нет.
     *
     * <p>Файл и обложку каталог отпускает сам, а вот у уточнителя на эту книгу заведена задача, и
     * без извещения он потратит на неё запрос к справочнику.
     */
    public void bookDeleted(BookDeleted event) {
        kafka.send(Topics.BOOK_DELETED, event.bookId().toString(), event);
    }

    /**
     * Владелец просит уточнить книгу заново.
     *
     * <p>Каталог только передаёт просьбу: спрашивать справочники — не его дело, а ждать ответа в
     * запросе пользователя нельзя, ответ приходит минутами позже.
     */
    public void enrichmentRequested(BookEnrichmentRequested event) {
        kafka.send(Topics.BOOK_ENRICHMENT_REQUESTED, event.bookId().toString(), event);
    }

    /** Просьба собрать архив. Собирает его тот, кто владеет файлами. */
    public void exportRequested(ExportRequested event) {
        kafka.send(Topics.EXPORT_REQUESTED, event.taskId().toString(), event);
    }
}
