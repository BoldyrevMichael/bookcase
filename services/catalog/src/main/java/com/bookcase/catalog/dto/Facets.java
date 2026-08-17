package com.bookcase.catalog.dto;

import java.util.List;

/**
 * Перечни значений с числом книг у каждого.
 *
 * <p>Считаются по тому же отбору, что и сама выдача, поэтому число рядом со значением — обещание:
 * нажал «Java (17)» и получил ровно семнадцать книг, а не пустой список.
 *
 * @param formats форматы
 * @param themes темы
 * @param languages языки
 * @param shelves полки
 */
public record Facets(
        List<FacetValue> formats,
        List<FacetValue> themes,
        List<FacetValue> languages,
        List<FacetValue> shelves) {

    public Facets {
        formats = List.copyOf(formats);
        themes = List.copyOf(themes);
        languages = List.copyOf(languages);
        shelves = List.copyOf(shelves);
    }

    /**
     * Одно значение перечня.
     *
     * @param value значение
     * @param count сколько книг ему отвечает
     */
    public record FacetValue(String value, long count) {}
}
