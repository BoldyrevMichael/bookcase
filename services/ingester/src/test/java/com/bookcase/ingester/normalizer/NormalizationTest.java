package com.bookcase.ingester.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.ingester.parser.filename.FilenameParser;
import com.bookcase.metadata.LanguageNormalizer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Приведение разнобоя к единому виду. */
class NormalizationTest {

    private final AuthorNormalizer authors = new AuthorNormalizer();
    private final IsbnNormalizer isbns = new IsbnNormalizer();
    private final TitleNormalizer titles = new TitleNormalizer();
    private final LanguageNormalizer languages = new LanguageNormalizer();
    private final FilenameParser filenames = new FilenameParser();

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("имя автора приводится к виду «Фамилия И. О.»")
    @CsvSource({
        "Александр Швец,               Швец А.",
        "Balaji Varanasi,              Varanasi B.",
        "Владимиров С.М.,              Владимиров С. М.",
        "'Стругацкий, Аркадий Натанович', Стругацкий А. Н.",
        "С. М. Владимиров,             Владимиров С. М.",
        "Аркадий Натанович Стругацкий, Стругацкий А. Н.",
        "МАРТИН,                       Мартин",
    })
    void authorsAreNormalized(String source, String expected) {
        assertThat(authors.normalize(source)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("имена без человека внутри отбрасываются")
    @ValueSource(strings = {"unknown", "Неизвестен", "anonymous"})
    @NullSource
    void meaninglessAuthorsAreDropped(String source) {
        assertThat(authors.normalize(source)).isNull();
    }

    @DisplayName("несколько авторов в одном поле разделяются")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Александр Швец, Balaji Varanasi", "Александр Швец и Balaji Varanasi"})
    void severalAuthorsAreSplit(String source) {
        assertThat(authors.normalizeAll(List.of(source))).containsExactly("Швец А.", "Varanasi B.");
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("ISBN приводится к тринадцати знакам, неверный отбрасывается")
    @CsvSource({
        "5-93286-153-3,       9785932861530",
        "978-5-17-118366-0,   9785171183660",
        "ISBN 0-306-40615-2,  9780306406157",
        "5-93286-153-2,       ",
        "просто текст,        ",
    })
    void isbnIsNormalized(String source, String expected) {
        assertThat(isbns.normalize(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("язык приводится к двухбуквенному коду")
    @CsvSource({
        "ru,       ru",
        "rus,      ru",
        "ru-RU,    ru",
        "Russian,  ru",
        "English,  en",
        "eng,      en",
        "клингон,  ",
    })
    void languageIsNormalized(String source, String expected) {
        assertThat(languages.normalize(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("название очищается от следов качалок и перестаёт кричать")
    @CsvSource({
        "'Мастер и Маргарита (fb2)',        Мастер и Маргарита",
        "'Мастер_и_Маргарита',              Мастер и Маргарита",
        "'СОВЕРШЕННЫЙ КОД',                 Совершенный код",
        "'Совершенный код   ',              Совершенный код",
        "'Java EE',                         Java EE",
    })
    void titleIsNormalized(String source, String expected) {
        assertThat(titles.normalize(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("имя файла разбирается на автора, название и год")
    @CsvSource({
        "'Беллемар А. - Микросервисы - 2020.epub',Беллемар А.,Микросервисы,2020",
        "'Швец А. - Погружение в паттерны (2018).pdf',Швец А.,Погружение в паттерны,2018",
        "'Мастер и Маргарита.fb2',,Мастер и Маргарита,",
        "'Кинг С. - Тёмная башня 02 - Извлечение троих.epub',Кинг С.,Извлечение троих,",
    })
    void filenameIsParsed(String name, String author, String title, String year) {
        var parsed = filenames.parse(name);

        assertThat(parsed.title()).isEqualTo(title);
        assertThat(parsed.date()).isEqualTo(year);
        if (author == null) {
            assertThat(parsed.authors()).isEmpty();
        } else {
            assertThat(parsed.authors()).containsExactly(author);
        }
    }

    @DisplayName("ISBN из имени файла попадает в карточку и не мешает разбору")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        // Так называют файлы издательские выгрузки; по ISBN справочник опознаёт издание точно.
        "'978-5-97060-180-8_Java Persistence API и Hibernate.pdf',978-5-97060-180-8,"
                + "Java Persistence API и Hibernate",
        // Имя из одного ISBN: названия нет, но книга опознаётся.
        "'9780134076423.pdf',9780134076423,",
    })
    void isbnIsTakenFromFilename(String name, String isbn, String title) {
        var parsed = filenames.parse(name);

        assertThat(parsed.isbn()).isEqualTo(isbn);
        assertThat(parsed.title()).isEqualTo(title);
        // Цифры ISBN не должны быть приняты за год издания.
        assertThat(parsed.date()).isNull();
    }

    @DisplayName("имя сплошь через дефис не рассыпается на части")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'(by-Ron-Dai)-Learn-Java-with-Math.pdf',Ron Dai,Learn Java with Math",
        "'by-David-Cuartielles,-Andreas-Gransson.pdf',David Cuartielles,",
    })
    void hyphenatedNamesAreNotShredded(String name, String firstAuthor, String title) {
        var parsed = filenames.parse(name);

        assertThat(parsed.authors()).first().isEqualTo(firstAuthor);
        assertThat(parsed.title()).isEqualTo(title);
    }

    @DisplayName("номер книги в серии вынимается из имени вместе с названием серии")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Кинг С. - Тёмная башня 02 - Извлечение троих.epub"})
    void seriesIsParsed(String name) {
        var parsed = filenames.parse(name);

        assertThat(parsed.series()).isEqualTo("Тёмная башня");
        assertThat(parsed.seriesNumber()).isEqualTo("2");
    }

    @DisplayName("мусорные пометки в имени не попадают в название")
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "Мастер и Маргарита [litres].fb2",
                "Мастер и Маргарита_ocr.pdf",
                "Мастер и Маргарита (1).epub"
            })
    void junkMarkersAreRemoved(String name) {
        assertThat(filenames.parse(name).title()).isEqualTo("Мастер и Маргарита");
    }
}
