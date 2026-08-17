package com.bookcase.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Книга появилась в библиотеке.
 *
 * <p>Событие несёт то, чего хватает для поиска по внешним справочникам: ISBN, название, авторов и
 * год. Читателю не приходится ходить за этим обратно в каталог.
 *
 * @param eventId идентификатор события
 * @param bookId карточка
 * @param ownerId владелец
 * @param sha256 файл в хранилище
 * @param title название, если известно
 * @param authors авторы
 * @param isbn ISBN
 * @param year год издания
 * @param occurredAt когда книга появилась
 */
public record BookAdded(
        UUID eventId,
        UUID bookId,
        String ownerId,
        String sha256,
        String title,
        List<String> authors,
        String isbn,
        Integer year,
        Instant occurredAt) {

    public BookAdded {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }
}
