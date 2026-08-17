package com.bookcase.ingester.normalizer;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Приведение названия к читаемому виду.
 *
 * <p>Названия приходят с хвостами вида «(fb2)» и «[litres]», с подчёркиваниями вместо пробелов и
 * иногда целиком прописными — так их оставляют программы распознавания. КРИЧАЩЕЕ НАЗВАНИЕ в списке
 * книг мешает читать соседние, поэтому оно приводится к обычному виду; названия из аббревиатур при
 * этом не трогаются.
 */
@Component
public class TitleNormalizer {

    // (?iu), а не (?i): без признака u безразличие к регистру действует только на латиницу,
    // и хвост «[СКАН]» прописными остался бы в названии.
    private static final Pattern JUNK =
            Pattern.compile(
                    "(?iu)[\\[(](fb2|epub|pdf|djvu|litres|z-lib\\.org|libgen|ocr|скан)[\\])]");
    private static final Pattern TRAILING_SEPARATORS = Pattern.compile("[\\s\\-–—_.,:;]+$");
    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[\\s\\-–—_.,:;]+");
    private static final int SHORTEST_SHOUTING = 6;

    public String normalize(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String cleaned =
                JUNK.matcher(title)
                        .replaceAll(" ")
                        .replace('_', ' ')
                        .replaceAll("\\s+", " ")
                        .trim();
        cleaned = LEADING_SEPARATORS.matcher(cleaned).replaceAll("");
        cleaned = TRAILING_SEPARATORS.matcher(cleaned).replaceAll("");
        if (cleaned.isEmpty()) {
            return null;
        }
        return isShouting(cleaned) ? toSentenceCase(cleaned) : cleaned;
    }

    private boolean isShouting(String title) {
        long letters = title.chars().filter(Character::isLetter).count();
        if (letters < SHORTEST_SHOUTING) {
            return false;
        }
        long upper = title.chars().filter(Character::isUpperCase).count();
        return upper == letters;
    }

    private String toSentenceCase(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }
}
