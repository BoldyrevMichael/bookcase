package com.bookcase.catalog.messaging;

import com.bookcase.catalog.repository.ProcessedEventRepository;
import com.bookcase.catalog.service.BookService;
import com.bookcase.events.BookEnriched;
import com.bookcase.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.stereotype.Component;

/**
 * Приём находок внешних справочников.
 *
 * <p>Что из присланного попадёт в карточку, решает каталог, а не уточнитель: только здесь известно,
 * откуда взялось каждое поле. Пустое дополняется, угаданное из имени файла уточняется, вычитанное
 * из самого файла и правки владельца остаются как есть.
 *
 * <p>Как и у разобранных метаданных, отдельного топика повторов нет — работа состоит из одной
 * записи в свою базу. Топик отказов есть: молча потерянное уточнение выглядит как «справочник
 * ничего не знает», а это разные вещи.
 *
 * <p>Темы справочника намеренно не применяются. Тема в нашей библиотеке — то, по чему владелец ищет
 * и раскладывает книги. У Open Library в этом поле попадаются библиотечные шифры вроде «Qa76.54», у
 * Google Books — рубрики «Computers», одинаковые у половины полки. Засорять ими словарь тем значит
 * сделать бесполезным и словарь, и отбор по нему. Событие темы несёт — их можно будет показать
 * подсказкой при ручной правке, и это решение обратимо.
 */
@Slf4j
@Component
public class EnrichmentListener {

    private final BookService books;
    private final ProcessedEventRepository processedEvents;

    public EnrichmentListener(BookService books, ProcessedEventRepository processedEvents) {
        this.books = books;
        this.processedEvents = processedEvents;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 2000),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = ".catalog.retry",
            dltTopicSuffix = Topics.CATALOG_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_ENRICHED, groupId = "catalog")
    public void onBookEnriched(BookEnriched event) {
        if (processedEvents.alreadyProcessed(event.eventId())) {
            log.debug("уточнение {} уже применено, повтор пропущен", event.eventId());
            return;
        }
        log.info(
                "книга {}: справочник {} предлагает «{}»",
                event.bookId(),
                event.provider(),
                event.title());
        books.applyExternal(event);
        processedEvents.markProcessed(event.eventId());
    }

    /** Уточнение потеряно: книга остаётся с тем, что дал файл. */
    @DltHandler
    public void onGaveUp(BookEnriched event) {
        log.warn("уточнение для книги {} применить не удалось", event.bookId());
    }
}
