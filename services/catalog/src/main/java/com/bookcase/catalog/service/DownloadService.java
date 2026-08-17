package com.bookcase.catalog.service;

import com.bookcase.catalog.client.StorageClient;
import com.bookcase.catalog.config.CatalogProperties;
import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.dto.DownloadLink;
import com.bookcase.catalog.exception.BookNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Ссылки на скачивание книги.
 *
 * <p>Имя файла вычисляется здесь же и по нынешней схеме: оно нигде не хранится, поэтому схему можно
 * переиграть, и всё, что уже загружено, начнёт скачиваться по-новому само.
 */
@Service
public class DownloadService {

    private final BookService books;
    private final StorageClient storage;
    private final CanonicalName names;
    private final CatalogProperties properties;

    public DownloadService(
            BookService books,
            StorageClient storage,
            CanonicalName names,
            CatalogProperties properties) {
        this.books = books;
        this.storage = storage;
        this.names = names;
        this.properties = properties;
    }

    /**
     * Обложка книги.
     *
     * <p>Проводится через каталог намеренно. В хранилище обложка адресуется хэшем содержимого, и
     * этого хэша достаточно, чтобы её получить, — а посчитать его может кто угодно, скачав ту же
     * картинку у справочника. Отдавая обложку только по идентификатору карточки, каталог сначала
     * убеждается, что книга принадлежит спрашивающему: у чужой книги нет ни обложки, ни ответа,
     * отличного от 404.
     *
     * @return содержимое картинки; пусто, если обложки у книги нет
     */
    public byte[] coverOf(UUID bookId, String ownerId, String userToken) {
        BookCard book = books.find(bookId, ownerId);
        if (book.coverSha256() == null) {
            throw new BookNotFoundException(bookId.toString());
        }
        return storage.fetchCover(book.coverSha256(), userToken);
    }

    public DownloadLink forBook(UUID bookId, String ownerId, String userToken) {
        BookCard book = books.find(bookId, ownerId);
        StorageClient.Ticket ticket = storage.issueBookTicket(book.sha256(), userToken);
        String url =
                "%s/api/v1/files/%s/download/%s"
                        .formatted(properties.storagePublicUrl(), book.sha256(), ticket.token());
        return new DownloadLink(url, names.forBook(book), Instant.parse(ticket.expiresAt()));
    }
}
