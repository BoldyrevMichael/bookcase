package com.bookcase.enricher.messaging;

import com.bookcase.enricher.client.StorageClient;
import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import com.bookcase.events.BookDeleted;
import com.bookcase.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Книгу удалили — снимаем всё, что было на неё заведено.
 *
 * <p>Задача уточнения иначе дождалась бы своей очереди и потратила запрос к справочнику на книгу,
 * которой нет: суточная квота невелика, и такие запросы отнимают её у живых книг.
 *
 * <p>Второе — обложка. Обычно её отпускает каталог сразу при удалении, но бывает и наоборот:
 * удаление случилось, пока справочник думал, и картинка приезжает к несуществующей карточке. Убрать
 * её должен тот, кто положил, — здесь это и делается.
 *
 * <p>Повторы здесь есть, в отличие от приёма новых книг. Разница существенная: там обработчик
 * делает одну запись в собственную базу, и ждать снаружи незачем, а тут он обращается к чужому
 * сервису. Недоступное хранилище — обычная временная неполадка, и правильный ответ на неё —
 * вернуться к событию позже, а не махнуть рукой. Обе операции повтор переживают спокойно: удалять
 * нечего, если задача уже снята, и отпускать нечего, если держателя уже нет.
 */
@Slf4j
@Component
public class BookDeletedListener {

    private final EnrichmentTaskRepository tasks;
    private final StorageClient storage;

    public BookDeletedListener(EnrichmentTaskRepository tasks, StorageClient storage) {
        this.tasks = tasks;
        this.storage = storage;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 5000),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = ".enricher.retry",
            dltTopicSuffix = Topics.ENRICHER_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_DELETED, groupId = "enricher")
    @Transactional
    public void onBookDeleted(BookDeleted event) {
        int removed = tasks.forget(event.bookId());
        if (removed > 0) {
            log.info("книга {} удалена, задача уточнения снята", event.bookId());
        }
        // Обложку отпускаем всегда: если её отпустил каталог, второй раз ничего не произойдёт,
        // а если она подоспела после удаления — уйдёт сейчас. Не вышло — событие вернётся
        // повтором вместе со снятием задачи: и то и другое можно делать сколько угодно раз.
        storage.releaseCover(event.bookId());
    }

    @DltHandler
    public void onGaveUp(BookDeleted event) {
        log.warn("известие об удалении книги {} обработать не удалось", event.bookId());
    }
}
