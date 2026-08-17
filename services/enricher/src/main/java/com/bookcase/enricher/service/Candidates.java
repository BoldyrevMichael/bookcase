package com.bookcase.enricher.service;

import com.bookcase.enricher.client.Candidate;

/**
 * Сборка ответа из нескольких справочников.
 *
 * <p>Первый принятый кандидат задаёт, о какой книге речь: название и авторы берутся у него, и
 * менять их на чужие нельзя — иначе карточка соберётся из двух разных изданий. А вот пустые места
 * дополнить можно и нужно: у Google Books чаще есть издательство, у Open Library — темы и обложка,
 * и спрашивать второго ради недостающего дешевле, чем оставлять карточку неполной.
 *
 * <p>Спрашивают второго не всегда: если первый ответил полно, следующего не тревожат — суточная
 * квота не бесконечна.
 */
public final class Candidates {

    private Candidates() {}

    /** Заполняет пустые поля первого кандидата значениями второго. */
    public static Candidate fillGaps(Candidate first, Candidate second) {
        return new Candidate(
                first.provider(),
                first.matchedBy(),
                first.title(),
                first.authors().isEmpty() ? second.authors() : first.authors(),
                first.year() == null ? second.year() : first.year(),
                blank(first.language()) ? second.language() : first.language(),
                blank(first.isbn()) ? second.isbn() : first.isbn(),
                blank(first.publisher()) ? second.publisher() : first.publisher(),
                first.themes().isEmpty() ? second.themes() : first.themes(),
                blank(first.coverUrl()) ? second.coverUrl() : first.coverUrl());
    }

    /**
     * Достаточно ли собрано, чтобы больше никого не спрашивать.
     *
     * <p>Темы в этот список не входят: каталог их всё равно не применяет, и ходить за ними к ещё
     * одному справочнику незачем.
     */
    public static boolean isComplete(Candidate candidate) {
        return notBlank(candidate.title())
                && !candidate.authors().isEmpty()
                && candidate.year() != null
                && notBlank(candidate.publisher())
                && notBlank(candidate.isbn())
                && notBlank(candidate.coverUrl());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean notBlank(String value) {
        return !blank(value);
    }
}
