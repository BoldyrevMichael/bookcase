package com.bookcase.enricher.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Проверки подготовки запроса.
 *
 * <p>Живой замер показал, что от неё зависит больше, чем от выбора справочника: то же название с
 * пунктуацией не находит ничего, без неё — находит книгу.
 */
class SearchTermsTest {

    @Test
    @DisplayName("Из названия убираются расширение файла, ISBN и пунктуация")
    void cleansTitle() {
        assertThat(SearchTerms.cleanTitle("978-5-97060-180-8 Java Persistence API и Hibernate"))
                .isEqualTo("Java Persistence API и Hibernate");
        assertThat(SearchTerms.cleanTitle("Д. Босуэлл, Т. Фаучер. Читаемый код.djvu"))
                .isEqualTo("Д Босуэлл Т Фаучер Читаемый код");
        assertThat(SearchTerms.cleanTitle("Kafka: The Definitive Guide: Real-Time Data"))
                .isEqualTo("Kafka The Definitive Guide Real Time Data");
    }

    @Test
    @DisplayName("Фамилия берётся без инициалов")
    void extractsSurname() {
        assertThat(SearchTerms.surname(List.of("Narkhede N.", "Shapira G."))).isEqualTo("Narkhede");
        assertThat(SearchTerms.surname(List.of("Маркес Г."))).isEqualTo("Маркес");
        assertThat(SearchTerms.surname(List.of())).isEmpty();
    }

    @Test
    @DisplayName("Слова-заполнители в сравнении названий не участвуют")
    void dropsNoiseWords() {
        assertThat(SearchTerms.significantWords("The Complete Guide to Docker"))
                .containsExactly("docker");
    }

    @Test
    @DisplayName("Число содержательным словом не считается")
    void numbersAreNotMeaningful() {
        assertThat(SearchTerms.hasMeaningfulWord(SearchTerms.significantWords("2252"))).isFalse();
        assertThat(SearchTerms.hasMeaningfulWord(SearchTerms.significantWords("4be534"))).isFalse();
        assertThat(SearchTerms.hasMeaningfulWord(SearchTerms.significantWords("Docker 2252")))
                .isTrue();
    }
}
