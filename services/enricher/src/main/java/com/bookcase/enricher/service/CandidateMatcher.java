package com.bookcase.enricher.service;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.config.EnricherProperties;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Решает, о той ли книге рассказал справочник.
 *
 * <p>Без этой проверки уточнение вредит больше, чем помогает. Замер на настоящем корпусе показал,
 * что «нашлось» и «нашлось верно» — разные вещи: на файл {@code 4be534.pdf} Open Library предложила
 * «Мастера и Маргариту», на {@code 2252.pdf} Google Books — том договоров ООН, а на «Database
 * Systems: A Practical Approach» пришло «Understanding Computers Today and Tomorrow». Владелец
 * такой подмены не заметит: карточка выглядит заполненной и правдоподобной.
 *
 * <p>Поэтому цена ошибок здесь несимметрична и правила намеренно строгие. Не принятый кандидат
 * оставляет книгу как была — её всегда можно уточнить руками. Принятый чужой кандидат портит
 * карточку молча.
 */
@Slf4j
@Component
public class CandidateMatcher {

    private final EnricherProperties.Match settings;

    public CandidateMatcher(EnricherProperties properties) {
        this.settings = properties.match();
    }

    /** Итог сверки: принят ли кандидат и почему. */
    public record Verdict(boolean accepted, String reason) {

        static Verdict yes(String reason) {
            return new Verdict(true, reason);
        }

        static Verdict no(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * Сверяет найденное с тем, что известно о книге.
     *
     * <p>Порядок проверок — от самой надёжной приметы к самой шаткой.
     */
    public Verdict check(Lookup lookup, Candidate candidate) {
        // ISBN опознаёт издание однозначно. Если спрашивали по нему, сверять больше нечего.
        if ("isbn".equals(candidate.matchedBy())) {
            return Verdict.yes("совпал ISBN");
        }

        Set<String> wanted = SearchTerms.significantWords(lookup.title());
        Set<String> offered = SearchTerms.significantWords(candidate.title());
        if (wanted.isEmpty() || offered.isEmpty()) {
            return Verdict.no("нечего сравнивать: название пустое");
        }

        Set<String> common = new HashSet<>(wanted);
        common.retainAll(offered);
        if (common.isEmpty()) {
            return Verdict.no("названия не пересекаются");
        }
        // Совпадение по одному числу совпадением не считается: именно так «2252» превращается
        // в «Treaty Series 2252».
        if (!SearchTerms.hasMeaningfulWord(common)) {
            return Verdict.no("совпали только числа и короткие обрывки");
        }

        // Покрытие считается в обе стороны и берётся лучшее. Иначе правильный кандидат с коротким
        // названием («Kafka» на «Kafka: The Definitive Guide: Real-Time Data...») выглядел бы
        // непохожим только потому, что в карточке название длиннее.
        double coverage =
                Math.max(
                        (double) common.size() / wanted.size(),
                        (double) common.size() / offered.size());

        boolean authorMatches = authorMatches(lookup, candidate);
        double required =
                authorMatches || lookup.authors().isEmpty() && candidate.authors().isEmpty()
                        ? settings.minCoverage()
                        : settings.coverageWithoutAuthor();
        if (coverage < required) {
            return Verdict.no(
                    "названия слишком разные: совпало %.0f%% при нужных %.0f%%"
                            .formatted(coverage * 100, required * 100));
        }

        // Автор, который есть у обоих и не совпал, — верный признак чужой книги.
        if (!authorMatches && !lookup.authors().isEmpty() && !candidate.authors().isEmpty()) {
            return Verdict.no("авторы разные");
        }

        if (!yearIsPlausible(lookup, candidate)) {
            return Verdict.no("год расходится слишком сильно");
        }
        return Verdict.yes(
                authorMatches
                        ? "совпали название и автор"
                        : "название совпало на %.0f%%".formatted(coverage * 100));
    }

    /**
     * Совпадает ли хоть одна фамилия.
     *
     * <p>Сравниваются именно фамилии: справочники пишут имя то полностью, то инициалами, то в
     * другом порядке, и требовать совпадения строки целиком значит не принять почти ничего.
     */
    private boolean authorMatches(Lookup lookup, Candidate candidate) {
        Set<String> known = SearchTerms.surnames(lookup.authors());
        Set<String> found = SearchTerms.surnames(candidate.authors());
        known.retainAll(found);
        return !known.isEmpty();
    }

    /**
     * Не выглядит ли год признаком совсем другой книги.
     *
     * <p>Расхождение в несколько лет — обычное дело: справочники охотно отдают последнее
     * переиздание, тогда как у владельца лежит издание постарше. А вот разница в поколение говорит
     * о том, что нашлась однофамильная книга.
     */
    private boolean yearIsPlausible(Lookup lookup, Candidate candidate) {
        if (lookup.year() == null || candidate.year() == null) {
            return true;
        }
        return Math.abs(lookup.year() - candidate.year()) <= settings.maxYearGap();
    }
}
