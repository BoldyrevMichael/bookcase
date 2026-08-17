package com.bookcase.ingester.normalizer;

import com.bookcase.events.BookFormat;
import com.bookcase.events.BookMetadata;
import com.bookcase.events.MetadataSource;
import com.bookcase.ingester.parser.RawMetadata;
import com.bookcase.metadata.LanguageNormalizer;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Сборка карточки из нескольких источников.
 *
 * <p>Поля берутся по одному, а не целиком из «лучшего» источника: у книги может быть название
 * внутри файла и год только в имени файла, и терять второе из-за первого незачем. Для каждого поля
 * запоминается, откуда оно взялось, — потом это позволит уточнению из интернета дописать пустое и
 * не трогать то, что исправил человек.
 *
 * <p>Ничего не выдумывается: если поле не нашлось ни в файле, ни в имени, оно остаётся пустым, и
 * карточка честно требует просмотра глазами.
 */
@Component
public class MetadataAssembler {

    private static final Pattern YEAR = Pattern.compile("(1[4-9]\\d{2}|20\\d{2})");

    private final AuthorNormalizer authors;
    private final TitleNormalizer titles;
    private final IsbnNormalizer isbns;
    private final LanguageNormalizer languages;

    public MetadataAssembler(
            AuthorNormalizer authors,
            TitleNormalizer titles,
            IsbnNormalizer isbns,
            LanguageNormalizer languages) {
        this.authors = authors;
        this.titles = titles;
        this.isbns = isbns;
        this.languages = languages;
    }

    public BookMetadata assemble(
            BookFormat format, RawMetadata embedded, RawMetadata fromFilename) {
        Map<String, MetadataSource> sources = new HashMap<>();

        String title =
                pick(
                        "title",
                        titles.normalize(embedded.title()),
                        titles.normalize(fromFilename.title()),
                        sources);
        List<String> authorList = pickAuthors(embedded, fromFilename, sources);
        Integer year = pick("year", year(embedded.date()), year(fromFilename.date()), sources);
        String language =
                pick(
                        "language",
                        languages.normalize(embedded.language()),
                        languages.normalize(fromFilename.language()),
                        sources);
        String isbn =
                pick(
                        "isbn",
                        isbns.normalize(embedded.isbn()),
                        isbns.normalize(fromFilename.isbn()),
                        sources);
        String series =
                pick(
                        "series",
                        titles.normalize(embedded.series()),
                        titles.normalize(fromFilename.series()),
                        sources);
        Integer seriesNumber =
                pick(
                        "seriesNumber",
                        number(embedded.seriesNumber()),
                        number(fromFilename.seriesNumber()),
                        sources);
        String publisher =
                pick(
                        "publisher",
                        trimToNull(embedded.publisher()),
                        trimToNull(fromFilename.publisher()),
                        sources);

        return new BookMetadata(
                format,
                title,
                authorList,
                year,
                language,
                isbn,
                series,
                seriesNumber,
                publisher,
                sources);
    }

    private List<String> pickAuthors(
            RawMetadata embedded, RawMetadata fromFilename, Map<String, MetadataSource> sources) {
        List<String> fromFile = authors.normalizeAll(embedded.authors());
        if (!fromFile.isEmpty()) {
            sources.put("authors", MetadataSource.EMBEDDED);
            return fromFile;
        }
        List<String> guessed = authors.normalizeAll(fromFilename.authors());
        if (!guessed.isEmpty()) {
            sources.put("authors", MetadataSource.FILENAME);
        }
        return guessed;
    }

    private <T> T pick(
            String field, T embedded, T fromFilename, Map<String, MetadataSource> sources) {
        if (embedded != null) {
            sources.put(field, MetadataSource.EMBEDDED);
            return embedded;
        }
        if (fromFilename != null) {
            sources.put(field, MetadataSource.FILENAME);
        }
        return fromFilename;
    }

    /** Дата приходит в любом виде — от «2018» до «2018-05-01T00:00:00Z»; нужен только год. */
    private Integer year(String date) {
        if (date == null) {
            return null;
        }
        Matcher matcher = YEAR.matcher(date);
        if (!matcher.find()) {
            return null;
        }
        int value = Integer.parseInt(matcher.group(1));
        return value <= Year.now(ZoneOffset.UTC).getValue() ? value : null;
    }

    private Integer number(String value) {
        if (value == null) {
            return null;
        }
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
