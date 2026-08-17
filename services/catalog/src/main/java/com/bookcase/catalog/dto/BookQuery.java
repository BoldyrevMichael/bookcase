package com.bookcase.catalog.dto;

import com.bookcase.catalog.service.state.Shelf;
import com.bookcase.events.BookFormat;
import java.util.List;
import java.util.UUID;

/**
 * Отбор книг.
 *
 * @param text строка поиска; пусто — значит весь список
 * @param scope где искать: пока только по описанию книги. Параметр введён сразу, хотя значение у
 *     него одно: когда появится поиск по содержимому, у существующих клиентов ничего не сломается
 * @param formats отбор по формату
 * @param themes отбор по темам
 * @param languages отбор по языку
 * @param yearFrom год издания не раньше
 * @param yearTo год издания не позже
 * @param shelf отбор по полке
 * @param favorite только избранное
 * @param collectionId только книги из подборки
 * @param sort порядок
 * @param cursor место, с которого продолжать
 * @param limit сколько вернуть
 */
public record BookQuery(
        String text,
        SearchScope scope,
        List<BookFormat> formats,
        List<String> themes,
        List<String> languages,
        Integer yearFrom,
        Integer yearTo,
        Shelf shelf,
        Boolean favorite,
        UUID collectionId,
        BookSort sort,
        String cursor,
        int limit) {

    public BookQuery {
        formats = formats == null ? List.of() : List.copyOf(formats);
        themes = themes == null ? List.of() : List.copyOf(themes);
        languages = languages == null ? List.of() : List.copyOf(languages);
        scope = scope == null ? SearchScope.METADATA : scope;
        sort = sort == null ? BookSort.ADDED : sort;
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
