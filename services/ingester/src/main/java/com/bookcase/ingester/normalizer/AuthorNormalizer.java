package com.bookcase.ingester.normalizer;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Приведение имени автора к виду «Фамилия И. О.».
 *
 * <p>Фамилия впереди — не вкус, а условие сортировки: имена файлов и списки упорядочиваются
 * посимвольно, и если у одного автора где-то записано «Александр Швец», а где-то «Швец А.», его
 * книги разъезжаются по разным местам алфавита, и собрать их вместе уже нечем.
 *
 * <p>Какая часть фамилия, определяется так: если среди частей есть инициалы — одиночные буквы, — то
 * фамилия та, что не инициал, где бы она ни стояла. Иначе фамилией считается последняя часть; это
 * верно и для «Александр Швец», и для «Balaji Varanasi». Запятая, если она есть, решает всё сама:
 * перед ней всегда фамилия.
 */
@Component
public class AuthorNormalizer {

    private static final List<String> IGNORED =
            List.of("unknown", "неизвестен", "неизвестный автор", "anonymous", "аноним", "n/a");

    public List<String> normalizeAll(List<String> authors) {
        return authors.stream()
                .flatMap(author -> splitJoined(author).stream())
                .map(this::normalize)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
    }

    /** В одном поле нередко перечислены несколько человек через запятую с «и» или «and». */
    private List<String> splitJoined(String value) {
        if (value == null) {
            return List.of();
        }
        if (value.matches(".+,\\s*\\p{L}\\.?\\s*\\p{L}?\\.?\\s*")) {
            // «Швец, А. С.» — это один человек, а не список.
            return List.of(value);
        }
        return Arrays.stream(value.split("\\s*(?:;|,| и | and | & )\\s*"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    public String normalize(String author) {
        if (author == null || author.isBlank()) {
            return null;
        }
        String cleaned = author.replaceAll("\\s+", " ").trim();
        if (IGNORED.contains(cleaned.toLowerCase(Locale.ROOT))) {
            return null;
        }

        String surname;
        List<String> rest;
        if (cleaned.contains(",")) {
            String[] halves = cleaned.split(",", 2);
            surname = halves[0].trim();
            rest = words(halves[1]);
        } else {
            List<String> words = words(cleaned);
            if (words.size() == 1) {
                return capitalize(words.get(0));
            }
            int surnameIndex = surnameIndex(words);
            surname = words.get(surnameIndex);
            rest = new java.util.ArrayList<>(words);
            rest.remove(surnameIndex);
        }

        String initials =
                rest.stream()
                        .map(this::initial)
                        .filter(part -> !part.isEmpty())
                        .collect(Collectors.joining(" "));
        return initials.isEmpty() ? capitalize(surname) : capitalize(surname) + " " + initials;
    }

    private int surnameIndex(List<String> words) {
        for (int i = 0; i < words.size(); i++) {
            if (!isInitial(words.get(i))) {
                boolean othersAreInitials = true;
                for (int j = 0; j < words.size(); j++) {
                    if (j != i && !isInitial(words.get(j))) {
                        othersAreInitials = false;
                        break;
                    }
                }
                if (othersAreInitials) {
                    return i;
                }
            }
        }
        return words.size() - 1;
    }

    /**
     * Инициалы пишут и через пробел, и слитно: «С. М.» и «С.М.» — одно и то же, и второе
     * встречается ничуть не реже.
     */
    private boolean isInitial(String word) {
        return word.matches("\\p{L}") || word.matches("(\\p{L}\\.){1,3}");
    }

    /**
     * Из полного имени берётся первая буква, а слипшиеся инициалы разводятся: «Александр»
     * превращается в «А.», а «С.М.» — в «С. М.».
     */
    private String initial(String word) {
        String letters = word.replace(".", "").trim();
        if (letters.isEmpty()) {
            return "";
        }
        if (!isInitial(word)) {
            return letters.substring(0, 1).toUpperCase(Locale.ROOT) + ".";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < letters.length(); i++) {
            if (i > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(letters.charAt(i))).append('.');
        }
        return result.toString();
    }

    private List<String> words(String value) {
        return Arrays.stream(value.trim().split("\\s+")).filter(word -> !word.isEmpty()).toList();
    }

    /** ФАМИЛИЯ прописными — обычная беда сканов; в остальном регистр не трогаем. */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification =
                    "Регистр меняется ради того, как имя выглядит в списке книг. "
                            + "Ни доступа, ни сравнения на совпадение от этого не зависит, "
                            + "а приведение идёт с явной нейтральной локалью.")
    private String capitalize(String word) {
        String cleaned = word.trim();
        if (cleaned.length() > 1 && cleaned.equals(cleaned.toUpperCase(Locale.ROOT))) {
            return cleaned.substring(0, 1) + cleaned.substring(1).toLowerCase(Locale.ROOT);
        }
        return cleaned;
    }
}
