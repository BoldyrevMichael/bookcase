package com.bookcase.catalog.dto;

import com.bookcase.catalog.service.state.Shelf;
import com.bookcase.events.BookFormat;
import java.util.List;
import java.util.UUID;

/**
 * Запрос поиска в том виде, в каком он приходит строкой адреса.
 *
 * <p>Отдельная запись, а не полтора десятка параметров у метода: у поиска в библиотеке фильтров
 * много, и перечислять их по одному в подписи — значит получить подпись, которую нельзя прочитать и
 * в которой легко перепутать местами два соседних значения одного типа.
 *
 * @param q строка поиска
 * @param in где искать
 * @param format отбор по формату
 * @param theme отбор по темам
 * @param language отбор по языку
 * @param yearFrom год издания не раньше
 * @param yearTo год издания не позже
 * @param shelf отбор по полке
 * @param favorite только избранное
 * @param collection только книги из подборки
 * @param sort порядок
 * @param cursor место, с которого продолжать
 * @param limit сколько вернуть
 */
public record BookSearchRequest(
        String q,
        SearchScope in,
        List<BookFormat> format,
        List<String> theme,
        List<String> language,
        Integer yearFrom,
        Integer yearTo,
        Shelf shelf,
        Boolean favorite,
        UUID collection,
        BookSort sort,
        String cursor,
        Integer limit) {

    public BookSearchRequest {
        // Списки приходят из строки адреса; наружу они больше не меняются.
        format = format == null ? null : List.copyOf(format);
        theme = theme == null ? null : List.copyOf(theme);
        language = language == null ? null : List.copyOf(language);
    }

    public BookQuery toQuery(int pageSize) {
        return new BookQuery(
                q,
                in,
                format,
                theme,
                language,
                yearFrom,
                yearTo,
                shelf,
                favorite,
                collection,
                sort,
                cursor,
                pageSize);
    }
}
