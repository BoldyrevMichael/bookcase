package com.bookcase.enricher.dto;

import com.bookcase.enricher.client.Lookup;
import java.util.List;
import java.util.UUID;

/**
 * Задача уточнения: одна книга, ждущая ответа справочника.
 *
 * @param bookId карточка
 * @param ownerId владелец
 * @param title название на момент появления книги
 * @param authors авторы через запятую, как их записал разбор
 * @param isbn ISBN, если был
 * @param year год издания
 * @param attempts сколько попыток уже сделано
 */
public record EnrichmentTask(
        UUID bookId,
        String ownerId,
        String title,
        String authors,
        String isbn,
        Integer year,
        int attempts) {

    /** То, с чем идут к справочнику. */
    public Lookup toLookup() {
        List<String> names =
                authors == null || authors.isBlank()
                        ? List.of()
                        : List.of(authors.split("\\s*,\\s*"));
        return new Lookup(title, names, isbn, year);
    }
}
