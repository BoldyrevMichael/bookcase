package com.bookcase.ingester.normalizer;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Приведение ISBN к виду ISBN-13.
 *
 * <p>Один и тот же номер записывают и в десяти знаках, и в тринадцати, с дефисами и без. Пока они
 * разные, книгу нельзя ни сопоставить с внешним справочником, ни опознать повторную загрузку
 * другого издания. Десятизначный переводится в тринадцатизначный по правилу: приписать префикс 978
 * и пересчитать контрольную цифру.
 *
 * <p>Контрольная цифра проверяется у обоих видов. Не сошлась — значит, это не ISBN, а опечатка, и
 * лучше не иметь номера, чем иметь неверный.
 */
@Component
public class IsbnNormalizer {

    public String normalize(String isbn) {
        if (isbn == null || isbn.isBlank()) {
            return null;
        }
        String digits = isbn.toUpperCase(Locale.ROOT).replaceAll("[^0-9X]", "");
        if (digits.length() == 10) {
            return isValidIsbn10(digits) ? toIsbn13(digits) : null;
        }
        if (digits.length() == 13) {
            return isValidIsbn13(digits) ? digits : null;
        }
        return null;
    }

    private boolean isValidIsbn10(String isbn) {
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char symbol = isbn.charAt(i);
            int value = symbol == 'X' ? 10 : symbol - '0';
            if (value < 0 || value > 10 || (symbol == 'X' && i != 9)) {
                return false;
            }
            sum += value * (10 - i);
        }
        return sum % 11 == 0;
    }

    private boolean isValidIsbn13(String isbn) {
        if (!isbn.chars().allMatch(Character::isDigit)) {
            return false;
        }
        return isbn.charAt(12) - '0' == checkDigit13(isbn.substring(0, 12));
    }

    private String toIsbn13(String isbn10) {
        String body = "978" + isbn10.substring(0, 9);
        return body + checkDigit13(body);
    }

    private int checkDigit13(String twelveDigits) {
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int value = twelveDigits.charAt(i) - '0';
            sum += (i % 2 == 0) ? value : value * 3;
        }
        return (10 - sum % 10) % 10;
    }
}
