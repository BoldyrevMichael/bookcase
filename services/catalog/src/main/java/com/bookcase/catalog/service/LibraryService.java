package com.bookcase.catalog.service;

import com.bookcase.catalog.client.StorageClient;
import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.messaging.EventPublisher;
import com.bookcase.events.BookDeleted;
import com.bookcase.events.BookEnrichmentRequested;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Действия, затрагивающие и каталог, и хранилище.
 *
 * <p>Удаление книги — как раз такое: карточка уходит из каталога, а файл освобождается в хранилище.
 * Порядок здесь важен. Сначала карточка, потом файл: если второй шаг не удастся, в хранилище
 * останется объект, на который никто не ссылается, — это лишнее место и не более. Обратный порядок
 * оставил бы карточку на несуществующий файл, то есть книгу, которая видна, открывается и не
 * скачивается.
 */
@Slf4j
@Service
public class LibraryService {

    private final BookService books;
    private final StorageClient storage;
    private final EventPublisher events;

    public LibraryService(BookService books, StorageClient storage, EventPublisher events) {
        this.books = books;
        this.storage = storage;
        this.events = events;
    }

    /**
     * Просит уточнить книгу заново.
     *
     * <p>Книга проверяется на владельца прежде всего: чужая карточка отвечает тем же, чем
     * несуществующая, и уточнить её нельзя, как нельзя открыть или скачать.
     */
    public void requestEnrichment(UUID bookId, String ownerId) {
        BookCard book = books.find(bookId, ownerId);
        events.enrichmentRequested(
                new BookEnrichmentRequested(
                        UUID.randomUUID(),
                        bookId,
                        ownerId,
                        book.title(),
                        book.authors(),
                        book.isbn(),
                        book.year(),
                        Instant.now()));
        log.info("книга {} отправлена на повторное уточнение", bookId);
    }

    public void remove(UUID bookId, String ownerId, String userToken) {
        String sha256 = books.delete(bookId, ownerId);
        try {
            storage.releaseFile(sha256, userToken);
        } catch (RuntimeException storageUnavailable) {
            log.warn(
                    "карточка {} удалена, но хранилище не отпустило файл {}",
                    bookId,
                    sha256,
                    storageUnavailable);
        }
        // Обложка отпускается отдельно и так же, как файл: хранилище убирает её, только когда
        // её не показывает больше ни одна карточка. Иначе картинка пережила бы книгу.
        try {
            storage.releaseCover(bookId, userToken);
        } catch (RuntimeException storageUnavailable) {
            log.warn(
                    "карточка {} удалена, но хранилище не отпустило обложку",
                    bookId,
                    storageUnavailable);
        }
        // Уточнитель узнаёт об удалении только отсюда: у него на эту книгу заведена задача,
        // и она дождётся очереди и потратит запрос к справочнику, если её не снять. Он же
        // уберёт обложку, если та подоспела уже после удаления карточки.
        events.bookDeleted(new BookDeleted(UUID.randomUUID(), bookId, ownerId, Instant.now()));
    }
}
