package com.bookcase.enricher.client;

import java.util.List;

/**
 * Ответ справочника об одной книге.
 *
 * <p>Именно кандидат, а не результат: справочник отвечает на запрос тем, что счёл похожим, и это
 * регулярно оказывается другой книгой. На корпусе видно, что бывает: по имени файла {@code
 * 4be534.pdf} один справочник предложил «Мастера и Маргариту», а по «2252» другой — том договоров
 * ООН. Поэтому кандидат обязан пройти сверку, прежде чем попасть в карточку.
 *
 * <p>Чужой формат ответа наружу не выходит: и Google Books, и Open Library разбираются здесь и
 * дальше едут в этом общем виде.
 *
 * @param provider имя справочника, который ответил
 * @param matchedBy по какому запросу нашлось — попадает в журнал и объясняет находку
 * @param title название
 * @param authors авторы, как их пишет справочник
 * @param year год издания найденного издания
 * @param language язык двухбуквенным кодом
 * @param isbn ISBN-13
 * @param publisher издательство
 * @param themes темы справочника
 * @param coverUrl адрес обложки
 */
public record Candidate(
        String provider,
        String matchedBy,
        String title,
        List<String> authors,
        Integer year,
        String language,
        String isbn,
        String publisher,
        List<String> themes,
        String coverUrl) {

    public Candidate {
        authors = authors == null ? List.of() : List.copyOf(authors);
        themes = themes == null ? List.of() : List.copyOf(themes);
    }
}
