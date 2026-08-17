package com.bookcase.enricher.client;

import java.util.List;

/**
 * Что известно о книге на момент обращения к справочнику.
 *
 * <p>Это снимок карточки, а не сама карточка: уточнитель работает в фоне, спустя время после
 * появления книги, и заново ходить за данными в каталог ему незачем — всё нужное приехало в
 * событии.
 *
 * @param title название, каким его удалось получить из файла или имени
 * @param authors авторы в виде «Фамилия И. О.»
 * @param isbn ISBN, если он был в файле
 * @param year год издания
 */
public record Lookup(String title, List<String> authors, String isbn, Integer year) {

    public Lookup {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    /** Есть ли вообще с чем идти к справочнику. */
    public boolean isSearchable() {
        boolean hasIsbn = isbn != null && !isbn.isBlank();
        boolean hasTitle = title != null && !title.isBlank();
        return hasIsbn || hasTitle;
    }
}
