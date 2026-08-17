package com.bookcase.catalog.dto;

import java.util.List;

/**
 * Страница выдачи.
 *
 * @param items книги
 * @param nextCursor место, с которого продолжать; пусто — дальше ничего нет
 * @param facets что ещё есть в библиотеке при этом же отборе
 */
public record BookPage(List<BookCard> items, String nextCursor, Facets facets) {

    public BookPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
