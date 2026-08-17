package com.bookcase.catalog.messaging;

import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.repository.ProcessedEventRepository;
import com.bookcase.catalog.service.BookService;
import com.bookcase.events.BookAdded;
import com.bookcase.events.BookMetadataExtracted;
import com.bookcase.events.Topics;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.stereotype.Component;

/**
 * Приём разобранных метаданных.
 *
 * <p>Отдельного топика повторов здесь нет: работа короткая и не зависит от чужой доступности — это
 * одна запись в базу. Неудача означает, что недоступна сама база, и тогда ждать в потребителе не
 * хуже, чем перекладывать сообщение. Топик окончательных отказов есть: карточка, потерянная молча,
 * — это книга, которой в библиотеке не появилось, и знать об этом нужно.
 */
@Slf4j
@Component
public class MetadataListener {

    private final BookService books;
    private final ProcessedEventRepository processedEvents;
    private final EventPublisher events;

    public MetadataListener(
            BookService books, ProcessedEventRepository processedEvents, EventPublisher events) {
        this.books = books;
        this.processedEvents = processedEvents;
        this.events = events;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 2000),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = ".catalog.retry",
            dltTopicSuffix = Topics.CATALOG_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_METADATA_EXTRACTED, groupId = "catalog")
    public void onMetadataExtracted(BookMetadataExtracted event) {
        if (processedEvents.alreadyProcessed(event.eventId())) {
            log.info("событие {} уже обработано, повтор пропущен", event.eventId());
            return;
        }
        books.createFromMetadata(
                        event.ownerId(), event.sha256(), event.originalName(), event.metadata())
                .ifPresent(card -> announce(card, event));
        processedEvents.markProcessed(event.eventId());
    }

    @DltHandler
    public void onGaveUp(BookMetadataExtracted event) {
        log.warn("карточку по файлу {} завести не удалось", event.sha256());
    }

    private void announce(BookCard card, BookMetadataExtracted event) {
        log.info("книга {} добавлена в библиотеку", card.id());
        events.bookAdded(
                new BookAdded(
                        UUID.randomUUID(),
                        card.id(),
                        event.ownerId(),
                        card.sha256(),
                        card.title(),
                        card.authors(),
                        card.isbn(),
                        card.year(),
                        Instant.now()));
    }
}
