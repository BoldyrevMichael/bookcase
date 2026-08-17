package com.bookcase.events;

import java.util.List;
import java.util.Map;

/**
 * Метаданные книги, приведённые к единому виду.
 *
 * <p>Незаполненные поля остаются пустыми: ничего не выдумывается. Карточка с пустым названием — это
 * карточка, которую нужно посмотреть глазами, а не повод подставить имя файла.
 *
 * @param format формат, определённый по содержимому
 * @param title название
 * @param authors авторы в виде «Фамилия И. О.»
 * @param year год издания
 * @param language язык двухбуквенным кодом
 * @param isbn ISBN в виде ISBN-13
 * @param series название серии
 * @param seriesNumber номер в серии
 * @param publisher издательство
 * @param sources источник каждого заполненного поля; ключ — имя поля
 */
public record BookMetadata(
        BookFormat format,
        String title,
        List<String> authors,
        Integer year,
        String language,
        String isbn,
        String series,
        Integer seriesNumber,
        String publisher,
        Map<String, MetadataSource> sources) {

    public BookMetadata {
        authors = authors == null ? List.of() : List.copyOf(authors);
        sources = sources == null ? Map.of() : Map.copyOf(sources);
    }

    /** Достаточно ли собрано, чтобы карточка не требовала просмотра глазами. */
    public boolean isComplete() {
        return title != null && !title.isBlank() && !authors.isEmpty();
    }
}
