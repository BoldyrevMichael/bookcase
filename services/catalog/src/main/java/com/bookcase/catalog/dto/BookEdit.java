package com.bookcase.catalog.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Правка карточки.
 *
 * <p>Пустое поле означает «не трогать», а не «стереть»: правка приходит частями, и обнулять то, о
 * чём не спрашивали, нельзя. Что человек изменил, помечается как исправленное им, и уточнение из
 * внешних источников этих полей больше не касается.
 *
 * @param title название
 * @param authors авторы
 * @param year год издания
 * @param language язык
 * @param isbn ISBN
 * @param series серия
 * @param seriesNumber номер в серии
 * @param publisher издательство
 * @param themes темы; передан пустой список — темы снимаются все
 */
public record BookEdit(
        @Size(max = 1000) String title,
        List<String> authors,
        Integer year,
        @Size(max = 8) String language,
        @Size(max = 20) String isbn,
        @Size(max = 500) String series,
        Integer seriesNumber,
        @Size(max = 300) String publisher,
        List<String> themes) {

    public BookEdit {
        // Пустое поле означает «не трогать», поэтому отсутствие списка сохраняется как есть,
        // а переданный список закрывается от изменений снаружи.
        authors = authors == null ? null : List.copyOf(authors);
        themes = themes == null ? null : List.copyOf(themes);
    }
}
