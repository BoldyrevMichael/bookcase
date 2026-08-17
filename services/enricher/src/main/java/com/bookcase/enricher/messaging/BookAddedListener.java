package com.bookcase.enricher.messaging;

import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import com.bookcase.enricher.repository.ProcessedEventRepository;
import com.bookcase.events.BookAdded;
import com.bookcase.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приём известий о появившихся книгах.
 *
 * <p>Топика повторов здесь нет намеренно. Обработчик делает ровно одну вставку в свою же таблицу —
 * такой работе паузы и лестница повторов не нужны, а единственная причина её неудачи в том, что
 * недоступна собственная база. Ждать снаружи в этом случае не лучше, чем ждать на месте.
 *
 * <p>Топик окончательных отказов есть: потерянное известие означает книгу, которая никогда не будет
 * уточнена, и об этом нужно знать. В его имени указан потребитель — у {@code book.added} может
 * появиться второй читатель со своими причинами падать.
 *
 * <p>Наружу отсюда не ходят вообще: справочник спрашивает фоновый работник по расписанию из базы.
 */
@Slf4j
@Component
public class BookAddedListener {

    private final EnrichmentTaskRepository tasks;
    private final ProcessedEventRepository processedEvents;

    public BookAddedListener(
            EnrichmentTaskRepository tasks, ProcessedEventRepository processedEvents) {
        this.tasks = tasks;
        this.processedEvents = processedEvents;
    }

    @RetryableTopic(
            attempts = "1",
            dltTopicSuffix = Topics.ENRICHER_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_ADDED, groupId = "enricher")
    @Transactional
    public void onBookAdded(BookAdded event) {
        if (processedEvents.alreadyProcessed(event.eventId())) {
            log.debug("событие {} уже учтено, повтор пропущен", event.eventId());
            return;
        }
        tasks.enqueue(
                event.bookId(),
                event.ownerId(),
                event.title(),
                String.join(", ", event.authors()),
                event.isbn(),
                event.year());
        processedEvents.markProcessed(event.eventId());
        log.info("книга {} поставлена в очередь на уточнение", event.bookId());
    }

    @DltHandler
    public void onGaveUp(
            BookAdded event,
            @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String reason) {
        log.warn("книгу {} не удалось поставить в очередь: {}", event.bookId(), reason);
    }
}
