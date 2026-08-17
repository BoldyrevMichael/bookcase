package com.bookcase.catalog.service;

import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.dto.BookEdit;
import com.bookcase.catalog.dto.BookPage;
import com.bookcase.catalog.dto.BookQuery;
import com.bookcase.catalog.exception.BookNotFoundException;
import com.bookcase.catalog.repository.AuthorRepository;
import com.bookcase.catalog.repository.BookRepository;
import com.bookcase.catalog.repository.ThemeRepository;
import com.bookcase.catalog.service.state.BookStatus;
import com.bookcase.catalog.service.state.Shelf;
import com.bookcase.events.BookEnriched;
import com.bookcase.events.BookMetadata;
import com.bookcase.events.MetadataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Карточки книг: заведение, поиск, правка и удаление. */
@Slf4j
@Service
public class BookService {

    private final BookRepository books;
    private final AuthorRepository authors;
    private final ThemeRepository themes;
    private final CursorCodec cursors;

    public BookService(
            BookRepository books,
            AuthorRepository authors,
            ThemeRepository themes,
            CursorCodec cursors) {
        this.books = books;
        this.authors = authors;
        this.themes = themes;
        this.cursors = cursors;
    }

    /**
     * Заводит карточку по разобранному файлу.
     *
     * @return карточка, если она появилась только что; пусто, если такая книга у владельца уже есть
     */
    @Transactional
    public Optional<BookCard> createFromMetadata(
            String ownerId, String sha256, String originalName, BookMetadata metadata) {
        UUID id = UUID.randomUUID();
        BookCard card =
                new BookCard(
                        id,
                        sha256,
                        originalName,
                        metadata.format(),
                        metadata.title(),
                        metadata.authors(),
                        metadata.year(),
                        metadata.language(),
                        metadata.isbn(),
                        metadata.series(),
                        metadata.seriesNumber(),
                        metadata.publisher(),
                        List.of(),
                        // Обложки при заведении карточки нет: её приносит уточнение из справочника.
                        null,
                        // Ни названия, ни автора — карточку нужно посмотреть глазами. Придумывать
                        // название из имени файла система не станет: неверные данные хуже пустых.
                        metadata.isComplete() ? BookStatus.READY : BookStatus.NEEDS_REVIEW,
                        Shelf.NONE,
                        false,
                        metadata.sources(),
                        null,
                        null);

        if (!books.insert(card, ownerId, String.join(" ", metadata.authors()))) {
            log.info(
                    "книга с файлом {} у владельца уже есть, вторая карточка не заводится", sha256);
            return Optional.empty();
        }
        linkAuthors(id, ownerId, metadata.authors());
        return books.find(id, ownerId).map(this::withRelations);
    }

    public BookCard find(UUID id, String ownerId) {
        BookCard card =
                books.find(id, ownerId).orElseThrow(() -> new BookNotFoundException(id.toString()));
        return withRelations(card);
    }

    /** Поиск с отбором, перечнями значений и продолжением с места остановки. */
    public BookPage search(BookQuery query, String ownerId) {
        List<BookRepository.SearchRow> found =
                books.search(query, ownerId, cursors.decode(query.cursor()).orElse(null));
        List<BookCard> cards =
                withRelations(found.stream().map(BookRepository.SearchRow::card).toList());
        // Место продолжения есть, только если страница заполнилась целиком: неполная означает,
        // что дальше ничего нет, и лишний запрос ради пустого ответа никому не нужен.
        String nextCursor = null;
        if (found.size() >= query.limit()) {
            BookRepository.SearchRow last = found.get(found.size() - 1);
            nextCursor = cursors.encode(last.sortValue(), last.card().id());
        }
        return new BookPage(cards, nextCursor, books.facets(query, ownerId));
    }

    /**
     * Правка карточки.
     *
     * <p>Каждое изменённое поле помечается как исправленное человеком, и уточнение из внешних
     * источников его больше не трогает: сказанное владельцем важнее любого справочника.
     */
    @Transactional
    public BookCard edit(UUID id, String ownerId, BookEdit edit) {
        BookCard current = find(id, ownerId);
        Map<String, MetadataSource> sources = new HashMap<>(current.sources());

        String title = replace("title", current.title(), edit.title(), sources);
        Integer year = replace("year", current.year(), edit.year(), sources);
        String language = replace("language", current.language(), edit.language(), sources);
        String isbn = replace("isbn", current.isbn(), edit.isbn(), sources);
        String series = replace("series", current.series(), edit.series(), sources);
        Integer seriesNumber =
                replace("seriesNumber", current.seriesNumber(), edit.seriesNumber(), sources);
        String publisher = replace("publisher", current.publisher(), edit.publisher(), sources);
        List<String> authorNames = current.authors();
        if (edit.authors() != null) {
            authorNames = List.copyOf(edit.authors());
            sources.put("authors", MetadataSource.USER);
            authors.unlinkAll(id);
            linkAuthors(id, ownerId, authorNames);
        }

        BookCard updated =
                new BookCard(
                        id,
                        current.sha256(),
                        current.originalName(),
                        current.format(),
                        title,
                        authorNames,
                        year,
                        language,
                        isbn,
                        series,
                        seriesNumber,
                        publisher,
                        current.themes(),
                        current.coverSha256(),
                        title != null && !title.isBlank() && !authorNames.isEmpty()
                                ? BookStatus.READY
                                : BookStatus.NEEDS_REVIEW,
                        current.shelf(),
                        current.favorite(),
                        sources,
                        current.createdAt(),
                        current.updatedAt());
        books.update(id, ownerId, updated, String.join(" ", authorNames));

        if (edit.themes() != null) {
            replaceThemes(id, ownerId, edit.themes());
        }
        return find(id, ownerId);
    }

    @Transactional
    public void setShelf(UUID id, String ownerId, Shelf shelf) {
        ensureExists(id, ownerId);
        books.updateShelf(id, ownerId, shelf);
    }

    @Transactional
    public void setFavorite(UUID id, String ownerId, boolean favorite) {
        ensureExists(id, ownerId);
        books.updateFavorite(id, ownerId, favorite);
    }

    /**
     * Удаляет карточку и сообщает, какой файл освободился.
     *
     * <p>Сам файл убирает хранилище: те же байты могут быть у другого владельца, и решать судьбу
     * объекта каталог не вправе.
     *
     * @return содержимое удалённой книги
     */
    @Transactional
    public String delete(UUID id, String ownerId) {
        BookCard card = find(id, ownerId);
        books.delete(id, ownerId);
        themes.deleteUnused(ownerId);
        return card.sha256();
    }

    public List<String> allFiles(String ownerId) {
        return books.findAllFiles(ownerId);
    }

    private void ensureExists(UUID id, String ownerId) {
        books.find(id, ownerId).orElseThrow(() -> new BookNotFoundException(id.toString()));
    }

    private void linkAuthors(UUID bookId, String ownerId, List<String> names) {
        List<UUID> ids =
                names.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> authors.findOrCreate(ownerId, name.trim()))
                        .toList();
        authors.link(bookId, ids);
    }

    private void replaceThemes(UUID bookId, String ownerId, List<String> names) {
        themes.unlinkAll(bookId);
        List<UUID> ids =
                names.stream()
                        .filter(name -> name != null && !name.isBlank())
                        .map(name -> themes.findOrCreate(ownerId, name.trim()))
                        .toList();
        themes.link(bookId, ids);
        themes.deleteUnused(ownerId);
    }

    /**
     * Дополняет карточку тем, что нашёл внешний справочник.
     *
     * <p>Правится только то, чего не было, и то, что было угадано из имени файла. Вычитанное из
     * самого файла и исправленное человеком остаётся нетронутым — в этом весь смысл того, что у
     * каждого поля хранится его происхождение.
     *
     * <p>Год — особый случай, и это выяснилось на живой проверке: справочники систематически отдают
     * последнее переиздание. На «Java Cookbook» 2014 года Google Books предлагает 2025-й, на «The
     * Kubernetes Book» 2017-го — 2023-й. Поэтому год дописывается только в пустое поле: угаданный
     * из имени файла год относится к тому файлу, который лежит у владельца, и он вернее.
     */
    @Transactional
    public void applyExternal(BookEnriched found) {
        UUID bookId = found.bookId();
        Optional<BookCard> existing = books.find(bookId, found.ownerId());
        if (existing.isEmpty()) {
            // Книгу успели удалить, пока справочник думал. Это не ошибка.
            log.info("уточнение пришло для книги {}, которой уже нет", bookId);
            return;
        }
        String ownerId = found.ownerId();
        BookCard current = withRelations(existing.get());
        Map<String, MetadataSource> sources = new HashMap<>(current.sources());

        String newTitle = fill("title", current.title(), found.title(), sources, true);
        Integer newYear = fill("year", current.year(), found.year(), sources, false);
        String newLanguage =
                fill(
                        "language",
                        current.language(),
                        twoLetterCode(found.language()),
                        sources,
                        true);
        String newIsbn = fill("isbn", current.isbn(), found.isbn(), sources, true);
        String newPublisher =
                fill("publisher", current.publisher(), found.publisher(), sources, true);

        List<String> newAuthors = current.authors();
        boolean guessed = sources.get("authors") == MetadataSource.FILENAME;
        if (!found.authors().isEmpty() && (current.authors().isEmpty() || guessed)) {
            newAuthors = List.copyOf(found.authors());
            sources.put("authors", MetadataSource.EXTERNAL);
            authors.unlinkAll(bookId);
            linkAuthors(bookId, ownerId, newAuthors);
        }

        BookCard updated =
                new BookCard(
                        bookId,
                        current.sha256(),
                        current.originalName(),
                        current.format(),
                        newTitle,
                        newAuthors,
                        newYear,
                        newLanguage,
                        newIsbn,
                        current.series(),
                        current.seriesNumber(),
                        newPublisher,
                        current.themes(),
                        // Обложка дописывается, только если её ещё не было: заменять уже
                        // показанную картинку на другую при каждом уточнении незачем.
                        current.coverSha256() == null ? found.coverSha256() : current.coverSha256(),
                        newTitle != null && !newTitle.isBlank() && !newAuthors.isEmpty()
                                ? BookStatus.READY
                                : BookStatus.NEEDS_REVIEW,
                        current.shelf(),
                        current.favorite(),
                        sources,
                        current.createdAt(),
                        current.updatedAt());
        books.update(bookId, ownerId, updated, String.join(" ", newAuthors));
        log.info("карточка {} дополнена данными справочника", bookId);
    }

    /**
     * Язык принимается только двухбуквенным кодом.
     *
     * <p>Уточнитель приводит его к этому виду сам, но карточка — последний рубеж: справочник это
     * чужая служба, и её ответ не должен ронять запись в базу. Однажды уже ронял — Open Library
     * прислала «eng» на поле в два символа.
     */
    private String twoLetterCode(String language) {
        return language != null && language.length() == 2 ? language : null;
    }

    /**
     * Записывает найденное, если поле свободно.
     *
     * @param overwriteGuessed можно ли поверх значения, угаданного из имени файла
     */
    private <T> T fill(
            String field,
            T current,
            T found,
            Map<String, MetadataSource> sources,
            boolean overwriteGuessed) {
        if (found == null || found instanceof String text && text.isBlank()) {
            return current;
        }
        boolean empty = current == null || current instanceof String text && text.isBlank();
        boolean guessed = sources.get(field) == MetadataSource.FILENAME;
        if (!empty && !(overwriteGuessed && guessed)) {
            return current;
        }
        sources.put(field, MetadataSource.EXTERNAL);
        return found;
    }

    /** Не переданное поле означает «не трогать», а не «стереть». */
    private <T> T replace(String field, T current, T edited, Map<String, MetadataSource> sources) {
        if (edited == null) {
            return current;
        }
        sources.put(field, MetadataSource.USER);
        return edited;
    }

    private BookCard withRelations(BookCard card) {
        return withRelations(List.of(card)).get(0);
    }

    /**
     * Дописывает авторов и темы. Двумя запросами на всю страницу, а не по два на книгу: тридцать
     * книг в выдаче превратились бы в шестьдесят обращений к базе.
     */
    private List<BookCard> withRelations(List<BookCard> cards) {
        List<UUID> ids = cards.stream().map(BookCard::id).toList();
        Map<UUID, List<String>> authorsByBook = authors.findByBooks(ids);
        Map<UUID, List<String>> themesByBook = themes.findByBooks(ids);
        List<BookCard> result = new ArrayList<>(cards.size());
        for (BookCard card : cards) {
            result.add(
                    new BookCard(
                            card.id(),
                            card.sha256(),
                            card.originalName(),
                            card.format(),
                            card.title(),
                            authorsByBook.getOrDefault(card.id(), List.of()),
                            card.year(),
                            card.language(),
                            card.isbn(),
                            card.series(),
                            card.seriesNumber(),
                            card.publisher(),
                            themesByBook.getOrDefault(card.id(), List.of()),
                            card.coverSha256(),
                            card.status(),
                            card.shelf(),
                            card.favorite(),
                            card.sources(),
                            card.createdAt(),
                            card.updatedAt()));
        }
        return result;
    }
}
