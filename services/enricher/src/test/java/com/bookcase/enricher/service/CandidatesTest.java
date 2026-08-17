package com.bookcase.enricher.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.enricher.client.Candidate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Проверки сборки ответа из нескольких справочников.
 *
 * <p>Случай не выдуманный: у Google Books чаще есть издательство, у Open Library — обложка, и
 * книга, уточнённая только первым, остаётся без картинки, хотя она существует.
 */
class CandidatesTest {

    private static Candidate candidate(
            String provider, String title, String publisher, String cover) {
        return new Candidate(
                provider,
                "название",
                title,
                List.of("Автор А."),
                2020,
                "ru",
                null,
                publisher,
                List.of(),
                cover);
    }

    @Test
    @DisplayName("второй справочник дополняет пустые места, но не переписывает найденное")
    void fillsOnlyGaps() {
        Candidate first = candidate("googlebooks", "Настоящее название", "О'Рейли", null);
        Candidate second =
                candidate("openlibrary", "Другое название", "Другое издательство", "http://cover");

        Candidate merged = Candidates.fillGaps(first, second);

        assertThat(merged.title()).isEqualTo("Настоящее название");
        assertThat(merged.publisher()).isEqualTo("О'Рейли");
        assertThat(merged.coverUrl()).isEqualTo("http://cover");
        // Кто ответил первым, тот и остаётся источником: карточка не должна собраться
        // из двух разных изданий.
        assertThat(merged.provider()).isEqualTo("googlebooks");
    }

    @Test
    @DisplayName("полный ответ виден сразу: следующего справочника не тревожат")
    void completeAnswerNeedsNoOneElse() {
        Candidate full =
                new Candidate(
                        "googlebooks",
                        "isbn",
                        "Название",
                        List.of("Автор А."),
                        2020,
                        "ru",
                        "9781492057611",
                        "О'Рейли",
                        List.of(),
                        "http://cover");

        assertThat(Candidates.isComplete(full)).isTrue();
        assertThat(Candidates.isComplete(candidate("googlebooks", "Название", null, null)))
                .isFalse();
    }
}
