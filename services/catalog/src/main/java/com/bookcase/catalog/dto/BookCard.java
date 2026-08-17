package com.bookcase.catalog.dto;

import com.bookcase.catalog.service.state.BookStatus;
import com.bookcase.catalog.service.state.Shelf;
import com.bookcase.events.BookFormat;
import com.bookcase.events.MetadataSource;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Карточка книги.
 *
 * @param id карточка
 * @param sha256 файл в хранилище
 * @param originalName имя, под которым файл загрузили
 * @param format формат, определённый по содержимому
 * @param title название
 * @param authors авторы в виде «Фамилия И. О.»
 * @param year год издания
 * @param language язык двухбуквенным кодом
 * @param isbn ISBN в виде ISBN-13
 * @param series серия
 * @param seriesNumber номер в серии
 * @param publisher издательство
 * @param themes темы — о чём книга
 * @param coverSha256 обложка в хранилище; пусто, если её не нашлось. Наружу не выдаётся: по этому
 *     хэшу картинку можно запросить у хранилища напрямую, а посчитать его несложно — достаточно
 *     скачать ту же обложку у справочника. Клиенту хватает признака и адреса {@code
 *     /api/v1/books/{id}/cover}, где владение проверяется
 * @param status готова карточка или требует просмотра глазами
 * @param shelf состояние чтения
 * @param favorite в избранном
 * @param sources источник каждого заполненного поля
 * @param createdAt когда книга появилась в библиотеке
 * @param updatedAt когда карточка менялась
 */
public record BookCard(
        UUID id,
        String sha256,
        String originalName,
        BookFormat format,
        String title,
        List<String> authors,
        Integer year,
        String language,
        String isbn,
        String series,
        Integer seriesNumber,
        String publisher,
        List<String> themes,
        @JsonIgnore String coverSha256,
        BookStatus status,
        Shelf shelf,
        boolean favorite,
        Map<String, MetadataSource> sources,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Есть ли у книги обложка. Именно это и нужно знать клиенту — сам хэш ему ни к чему.
     *
     * <p>Помечено явно: у записи Jackson сериализует её составляющие, а добавленные методы — только
     * по прямому указанию.
     */
    @JsonProperty("hasCover")
    public boolean hasCover() {
        return coverSha256 != null;
    }

    public BookCard {
        authors = authors == null ? List.of() : List.copyOf(authors);
        themes = themes == null ? List.of() : List.copyOf(themes);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }
}
