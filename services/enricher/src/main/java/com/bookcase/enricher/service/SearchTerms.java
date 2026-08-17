package com.bookcase.enricher.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Приведение названия и автора к виду, пригодному для внешнего поиска и для сверки.
 *
 * <p>Это не украшательство: на корпусе стратегия запроса решает больше, чем выбор справочника.
 * Название «Kafka: The Definitive Guide: Real-Time Data and Stream Processing at Scale», отданное
 * Open Library как есть, не находит ничего; оно же без пунктуации и вместе с фамилией автора
 * находит нужную книгу. Разница между нулём и половиной корпуса — здесь.
 */
public final class SearchTerms {

    /** ISBN, попавший в название из имени файла: «978-5-97060-180-8 Java Persistence API». */
    private static final Pattern ISBN_IN_TEXT = Pattern.compile("97[89][\\d\\s-]{10,17}");

    private static final Pattern EXTENSION =
            Pattern.compile("\\.(pdf|epub|djvu|fb2|txt|doc|indd)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}«»—–…]+");

    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** Инициал: «И.», «J» — в запросе они только мешают, а фамилия работает. */
    private static final Pattern INITIAL = Pattern.compile("\\p{Lu}\\.?");

    /** Слова, которые есть в половине технических названий и потому ничего не различают. */
    private static final Set<String> NOISE =
            Set.of(
                    "the",
                    "and",
                    "for",
                    "with",
                    "from",
                    "into",
                    "your",
                    "you",
                    "how",
                    "guide",
                    "book",
                    "edition",
                    "ed",
                    "vol",
                    "volume",
                    "using",
                    "learn",
                    "learning",
                    "practical",
                    "complete",
                    "introduction",
                    "beginners",
                    "programming",
                    "для",
                    "как",
                    "все",
                    "издание",
                    "введение");

    private SearchTerms() {}

    /**
     * Чистит название от следов имени файла и пунктуации.
     *
     * <p>Двоеточие убирается намеренно: подзаголовок после него обнуляет выдачу Open Library —
     * проверено живьём на настоящих карточках.
     */
    public static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String text = EXTENSION.matcher(title).replaceAll(" ");
        text = ISBN_IN_TEXT.matcher(text).replaceAll(" ");
        text = PUNCTUATION.matcher(text).replaceAll(" ");
        return SPACES.matcher(text).replaceAll(" ").trim();
    }

    /** Фамилия первого автора без инициалов: «Narkhede N., Shapira G.» → «Narkhede». */
    public static String surname(List<String> authors) {
        if (authors == null || authors.isEmpty()) {
            return "";
        }
        for (String author : authors) {
            for (String part : SPACES.split(cleanTitle(author))) {
                if (!part.isBlank() && !INITIAL.matcher(part).matches()) {
                    return part;
                }
            }
        }
        return "";
    }

    /** Все фамилии — по ним сверяется найденное. */
    public static Set<String> surnames(List<String> authors) {
        Set<String> result = new LinkedHashSet<>();
        if (authors == null) {
            return result;
        }
        for (String author : authors) {
            for (String part : SPACES.split(cleanTitle(author).toLowerCase(Locale.ROOT))) {
                if (part.length() > 2) {
                    result.add(part);
                }
            }
        }
        return result;
    }

    /**
     * Значимые слова названия: по ним считается похожесть.
     *
     * <p>Из них выброшены слова-заполнители вроде «the» и «guide»: если совпадение держится только
     * на них, это не совпадение.
     */
    public static Set<String> significantWords(String title) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : SPACES.split(cleanTitle(title).toLowerCase(Locale.ROOT))) {
            if (word.length() > 2 && !NOISE.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /**
     * Есть ли среди слов хоть одно содержательное — буквенное и не короткое.
     *
     * <p>Нужно против вырожденных совпадений: у файла с именем «2252.pdf» единственное слово
     * названия — число, и справочник охотно находит «Treaty Series 2252». Совпадение по числу
     * совпадением не считается.
     */
    public static boolean hasMeaningfulWord(Set<String> words) {
        return words.stream()
                .anyMatch(w -> w.length() > 2 && w.chars().noneMatch(Character::isDigit));
    }
}
