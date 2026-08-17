package com.bookcase.ingester.parser;

import java.util.List;

/**
 * То, что удалось прочитать из источника, ещё без приведения к единому виду.
 *
 * <p>Поля здесь строки, потому что источники пишут что угодно: год может оказаться полной датой,
 * язык — словом «Russian», а автор — как угодно переставленными частями имени. Разбираться с этим
 * будет нормализация, а дело разбора — прочитать и не потерять.
 *
 * @param title название
 * @param authors авторы как они записаны
 * @param date дата или год в исходном виде
 * @param language язык в исходном виде
 * @param isbn ISBN в исходном виде
 * @param series серия
 * @param seriesNumber номер в серии
 * @param publisher издательство
 */
public record RawMetadata(
        String title,
        List<String> authors,
        String date,
        String language,
        String isbn,
        String series,
        String seriesNumber,
        String publisher) {

    public RawMetadata {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }

    public static RawMetadata empty() {
        return new RawMetadata(null, List.of(), null, null, null, null, null, null);
    }

    /** Пустая ли выдача целиком: разбор прошёл, но метаданных в файле не оказалось. */
    public boolean isEmpty() {
        return isBlank(title)
                && authors.isEmpty()
                && isBlank(date)
                && isBlank(language)
                && isBlank(isbn)
                && isBlank(series)
                && isBlank(publisher);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
