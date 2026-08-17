package com.bookcase.ingester.parser.filename;

import com.bookcase.ingester.parser.RawMetadata;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Разбор имени файла.
 *
 * <p>Это не запасной вариант, а полноценный источник — и для DJVU с обычным текстом единственный.
 * Люди складывают книги осмысленно, и в имени обычно есть всё, чего нет внутри файла.
 *
 * <p>Сначала имя очищается от следов, которые оставляют качалки и распознавалки: пометки вроде
 * «_ocr» и «[litres]», номер копии в скобках, подчёркивания вместо пробелов. Затем из остатка
 * вынимается год и разбирается конструкция «Автор — Название».
 */
@Component
public class FilenameParser {

    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");
    private static final Pattern JUNK =
            Pattern.compile(
                    "(?i)([\\[(](litres|z-lib\\.org|libgen|flibusta|rulit(\\.\\w+)?)[\\])])"
                            + "|(_ocr\\b)|(\\bocr\\b)|(\\bfb2\\b)"
                            + "|(\\(\\d{1,3}\\)\\s*$)|(\\[\\d{1,3}\\])");
    private static final Pattern YEAR_IN_BRACKETS =
            Pattern.compile("[(\\[](1[4-9]\\d{2}|20\\d{2})[)\\]]");
    private static final Pattern YEAR_STANDALONE =
            Pattern.compile("(?<![\\d-])(1[4-9]\\d{2}|20\\d{2})(?![\\d-])");
    private static final Pattern SERIES_PREFIX =
            Pattern.compile("^(.+?)\\s+(\\d{1,3})\\s+-\\s+(.+)$");

    /** Разделителем считается только тире, окружённое пробелами. */
    private static final Pattern SPACED_DASH = Pattern.compile("\\s+[-–—]+\\s+");

    /** Пометка авторства, которую оставляют качалки: «(by Имя Фамилия)» в начале имени. */
    private static final Pattern BY_MARKER = Pattern.compile("(?i)^\\(?by\\s+([^)]+)\\)?\\s*(.*)$");

    private static final String SEPARATOR = " - ";

    /**
     * ISBN, вынесенный в имя файла.
     *
     * <p>Так называют файлы книжные магазины и издательские выгрузки: «978-5-97060-180-8_Java
     * Persistence API и Hibernate.pdf», а иногда именем служит один только ISBN —
     * «9780134076423.pdf». Взять его оттуда стоит: по ISBN внешний справочник опознаёт издание
     * однозначно, тогда как по названию нередко предлагает чужую книгу.
     */
    private static final Pattern ISBN_IN_NAME =
            Pattern.compile("(?<![\\d-])(97[89][\\d-]{10,14}|\\d{9}[\\dXx])(?![\\d-])");

    /** Части, которые встречаются вместо имени и означают, что имени нет. */
    private static final List<String> MEANINGLESS =
            List.of("book", "ebook", "document", "untitled", "scan", "unnamed");

    public RawMetadata parse(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return RawMetadata.empty();
        }
        String cleaned = clean(originalName);
        String isbn = extractIsbn(cleaned);
        String withoutIsbn = isbn == null ? cleaned : cleaned.replace(isbn, " ").trim();
        String year = extractYear(withoutIsbn);
        String withoutYear = removeYear(withoutIsbn, year);

        Matcher byMarker = BY_MARKER.matcher(withoutYear);
        if (byMarker.matches()) {
            return withIsbn(authoredBy(byMarker.group(1), byMarker.group(2), year), isbn);
        }

        String[] parts = withoutYear.split(Pattern.quote(SEPARATOR));
        if (parts.length >= 2) {
            String author = parts[0].trim();
            String title =
                    String.join(SEPARATOR, Arrays.copyOfRange(parts, 1, parts.length)).trim();
            return withIsbn(withSeries(author, title, year), isbn);
        }
        String title = withoutYear.trim();
        if (title.isEmpty() || isMeaningless(title)) {
            // Имя состояло из одного ISBN: названия нет, но опознать книгу этого достаточно.
            return isbn == null
                    ? RawMetadata.empty()
                    : new RawMetadata(null, List.of(), null, null, isbn, null, null, null);
        }
        return withIsbn(
                new RawMetadata(title, List.of(), year, null, null, null, null, null), isbn);
    }

    /** Год, найденный в самом ISBN, годом издания не является — поэтому ISBN убирается первым. */
    private String extractIsbn(String cleaned) {
        Matcher matcher = ISBN_IN_NAME.matcher(cleaned);
        return matcher.find() ? matcher.group(1) : null;
    }

    private RawMetadata withIsbn(RawMetadata metadata, String isbn) {
        if (isbn == null) {
            return metadata;
        }
        return new RawMetadata(
                metadata.title(),
                metadata.authors(),
                metadata.date(),
                metadata.language(),
                isbn,
                metadata.series(),
                metadata.seriesNumber(),
                metadata.publisher());
    }

    /**
     * Имя вида «Серия 02 - Название» встречается у книг цикла; ведущий ноль там стоит не случайно,
     * иначе десятая книга оказывается между первой и второй.
     */
    private RawMetadata withSeries(String author, String title, String year) {
        Matcher series = SERIES_PREFIX.matcher(title);
        if (series.matches()) {
            return new RawMetadata(
                    series.group(3).trim(),
                    List.of(author),
                    year,
                    null,
                    null,
                    series.group(1).trim(),
                    String.valueOf(Integer.parseInt(series.group(2))),
                    null);
        }
        return new RawMetadata(title, List.of(author), year, null, null, null, null, null);
    }

    /**
     * Разделитель «автор — название» — это тире с пробелами по обе стороны, и только оно.
     *
     * <p>Различие существенное. Скачанные файлы часто именуются сплошь через дефис, без пробелов
     * вовсе: «by-David-Cuartielles,-Andreas-Gransson». Если считать любой дефис разделителем, такое
     * имя рассыпается на десяток частей, первая из которых становится «автором», — проверка на
     * живом корпусе показала это сразу. Поэтому дефисы без пробелов считаются частью слов и в
     * именах без единого пробела заменяются на пробелы.
     */
    private String clean(String originalName) {
        String withoutExtension = EXTENSION.matcher(originalName).replaceAll("");
        String withoutJunk = JUNK.matcher(withoutExtension).replaceAll(" ").replace('_', ' ');
        String separated = SPACED_DASH.matcher(withoutJunk).replaceAll(SEPARATOR);
        if (!separated.contains(SEPARATOR) && separated.indexOf(' ') < 0) {
            separated = separated.replace('-', ' ');
        }
        return separated.replaceAll("\\s+", " ").trim();
    }

    /**
     * Имя с пометкой авторства: перечисленные в ней люди — авторы, а всё, что после, — название.
     * Названия может и не оказаться вовсе, и тогда карточка честно требует просмотра.
     */
    private RawMetadata authoredBy(String people, String rest, String year) {
        List<String> authors =
                Arrays.stream(people.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
        String title = rest.replaceAll("[-–—]+", " ").replaceAll("\\s+", " ").trim();
        return new RawMetadata(
                title.isEmpty() ? null : title, authors, year, null, null, null, null, null);
    }

    private String extractYear(String cleaned) {
        Matcher inBrackets = YEAR_IN_BRACKETS.matcher(cleaned);
        if (inBrackets.find()) {
            return acceptable(inBrackets.group(1));
        }
        Matcher standalone = YEAR_STANDALONE.matcher(cleaned);
        String found = null;
        while (standalone.find()) {
            found = standalone.group(1);
        }
        return acceptable(found);
    }

    /** Год из будущего — это не год издания, а часть названия или номер. */
    private String acceptable(String year) {
        if (year == null) {
            return null;
        }
        int value = Integer.parseInt(year);
        return value <= Year.now(ZoneOffset.UTC).getValue() ? year : null;
    }

    private String removeYear(String cleaned, String year) {
        if (year == null) {
            return cleaned;
        }
        return cleaned.replaceAll("[(\\[]" + year + "[)\\]]", " ")
                .replaceAll("(?<![\\d-])" + year + "(?![\\d-])", " ")
                .replaceAll("\\s+-\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isMeaningless(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return MEANINGLESS.contains(lower) || lower.matches("[0-9a-f]{8,}");
    }
}
