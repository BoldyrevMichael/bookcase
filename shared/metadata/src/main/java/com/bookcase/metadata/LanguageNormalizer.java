package com.bookcase.metadata;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Приведение языка к двухбуквенному коду.
 *
 * <p>Один и тот же язык встречается записанным как «ru», «rus», «ru-RU» и «Russian». Отбор книг по
 * языку возможен, только когда все они превращены во что-то одно.
 *
 * <p>Общий для сервисов: тем же кодом пользуются и разбор файлов, и уточнение из справочников. Open
 * Library отдаёт «eng», Google Books — «en», EPUB — что угодно из перечисленного.
 */
public class LanguageNormalizer {

    private static final Map<String, String> BY_NAME =
            Map.ofEntries(
                    Map.entry("russian", "ru"),
                    Map.entry("русский", "ru"),
                    Map.entry("english", "en"),
                    Map.entry("английский", "en"),
                    Map.entry("german", "de"),
                    Map.entry("немецкий", "de"),
                    Map.entry("french", "fr"),
                    Map.entry("французский", "fr"),
                    Map.entry("spanish", "es"),
                    Map.entry("ukrainian", "uk"),
                    Map.entry("украинский", "uk"));

    public String normalize(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        String value = language.trim().toLowerCase(Locale.ROOT);
        String named = BY_NAME.get(value);
        if (named != null) {
            return named;
        }
        String code = value.split("[-_]")[0];
        if (code.length() == 2) {
            return isKnown(code) ? code : null;
        }
        if (code.length() == 3) {
            return fromThreeLetters(code).orElse(null);
        }
        return null;
    }

    private boolean isKnown(String code) {
        return Arrays.asList(Locale.getISOLanguages()).contains(code);
    }

    private Optional<String> fromThreeLetters(String code) {
        return Arrays.stream(Locale.getISOLanguages())
                .filter(two -> Locale.of(two).getISO3Language().equals(code))
                .findFirst();
    }
}
