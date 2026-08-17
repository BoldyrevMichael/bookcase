package com.bookcase.enricher.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.config.EnricherProperties;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Проверки сверки кандидата.
 *
 * <p>Все случаи взяты из живого прогона по настоящему корпусу: и те, где справочник ответил верно,
 * и те, где он предложил постороннюю книгу. Это не выдуманные примеры — ровно так справочники и
 * отвечают.
 */
class CandidateMatcherTest {

    private final CandidateMatcher matcher =
            new CandidateMatcher(
                    new EnricherProperties(
                            new EnricherProperties.Google("", "", 1000),
                            new EnricherProperties.OpenLibrary("", "", true),
                            null,
                            10,
                            5,
                            null,
                            null,
                            new EnricherProperties.Match(0.5, 0.8, 30),
                            "http://localhost",
                            org.springframework.util.unit.DataSize.ofMegabytes(5),
                            java.time.Duration.ofSeconds(2),
                            java.time.Duration.ofSeconds(3)));

    private static Candidate candidate(String title, List<String> authors, Integer year) {
        return new Candidate(
                "test", "название", title, authors, year, null, null, null, List.of(), null);
    }

    @Test
    @DisplayName("Книга с бессмысленным именем файла не принимает никакого кандидата")
    void rejectsCandidateForMeaninglessName() {
        // Так и было: на файл 4be534.pdf Open Library предложила «Мастера и Маргариту».
        Lookup lookup = new Lookup("4be534", List.of(), null, 2019);
        Candidate offered =
                candidate("Мастер и Маргарита", List.of("Михаил Афанасьевич Булгаков"), 1966);

        CandidateMatcher.Verdict verdict = matcher.check(lookup, offered);

        assertThat(verdict.accepted()).isFalse();
    }

    @Test
    @DisplayName("Совпадение по одному числу совпадением не считается")
    void rejectsNumericOnlyMatch() {
        // На «2252» Google Books предложил том договоров ООН — совпало только число.
        Lookup lookup = new Lookup("2252", List.of(), null, 2013);
        Candidate offered = candidate("Treaty Series 2252", List.of("United Nations"), 2005);

        CandidateMatcher.Verdict verdict = matcher.check(lookup, offered);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("числа");
    }

    @Test
    @DisplayName("Чужая книга с непохожим названием отвергается")
    void rejectsDifferentBook() {
        Lookup lookup =
                new Lookup(
                        "Database Systems: A Practical Approach to Design",
                        List.of("Connolly T."),
                        null,
                        2015);
        Candidate offered =
                candidate(
                        "Understanding Computers Today and Tomorrow",
                        List.of("Deborah Morley"),
                        2003);

        assertThat(matcher.check(lookup, offered).accepted()).isFalse();
    }

    @Test
    @DisplayName("Короткое название справочника принимается, если совпал автор")
    void acceptsShortTitleWithMatchingAuthor() {
        // Справочник знает эту книгу как «Kafka», у нас записано полное название с подзаголовком.
        Lookup lookup =
                new Lookup(
                        "Kafka: The Definitive Guide: Real-Time Data and Stream Processing",
                        List.of("Narkhede N.", "Shapira G."),
                        null,
                        2017);
        Candidate offered = candidate("Kafka", List.of("Neha Narkhede", "Gwen Shapira"), 2017);

        CandidateMatcher.Verdict verdict = matcher.check(lookup, offered);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).contains("автор");
    }

    @Test
    @DisplayName("Совпавшие название и автор принимаются")
    void acceptsTitleAndAuthor() {
        Lookup lookup = new Lookup("The Kubernetes Book", List.of("Poulton N."), null, 2017);
        Candidate offered = candidate("The Kubernetes Book 2025", List.of("Nigel Poulton"), 2023);

        assertThat(matcher.check(lookup, offered).accepted()).isTrue();
    }

    @Test
    @DisplayName("Перевод под другим названием не принимается")
    void rejectsForeignTitle() {
        // На «Сто лет одиночества» Open Library отвечает испанским оригиналом: название другое,
        // фамилия записана латиницей и не совпадает.
        Lookup lookup = new Lookup("Сто лет одиночества", List.of("Маркес Г."), null, 1967);
        Candidate offered =
                candidate("Cien años de soledad", List.of("Gabriel García Márquez"), 1967);

        assertThat(matcher.check(lookup, offered).accepted()).isFalse();
    }

    @Test
    @DisplayName("Найденное по ISBN принимается без сверки названий")
    void acceptsIsbnMatch() {
        Lookup lookup =
                new Lookup("Сто лет одиночества", List.of("Маркес Г."), "9785271346873", 1967);
        Candidate offered =
                new Candidate(
                        "test",
                        "isbn",
                        "Сто лет одинотества",
                        List.of("Gabriel García Márquez"),
                        2011,
                        null,
                        "9785271346873",
                        null,
                        List.of(),
                        null);

        CandidateMatcher.Verdict verdict = matcher.check(lookup, offered);

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.reason()).contains("ISBN");
    }

    @Test
    @DisplayName("Издание, разошедшееся на поколение, отвергается")
    void rejectsFarYear() {
        Lookup lookup = new Lookup("Java Cookbook", List.of("Darwin I."), null, 1975);
        Candidate offered = candidate("Java Cookbook", List.of("Ian F. Darwin"), 2025);

        assertThat(matcher.check(lookup, offered).accepted()).isFalse();
    }

    @Test
    @DisplayName("Разные авторы при похожем названии — признак чужой книги")
    void rejectsDifferentAuthors() {
        Lookup lookup =
                new Lookup("Docker Tutorial for Beginners", List.of("Hutten D."), null, 2018);
        Candidate offered =
                candidate("Docker Tutorial for Beginners", List.of("James Smith"), 2018);

        CandidateMatcher.Verdict verdict = matcher.check(lookup, offered);

        assertThat(verdict.accepted()).isFalse();
        assertThat(verdict.reason()).contains("авторы");
    }
}
