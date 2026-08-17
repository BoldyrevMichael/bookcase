package com.bookcase.catalog.service;

import com.bookcase.catalog.dto.BookCard;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Имя, под которым книга сохраняется при скачивании.
 *
 * <p>Схема: {@code Фамилия И. - Название (Год).ext}. Фамилия впереди не ради красоты: имена файлов
 * сортируются посимвольно, и если у одного автора где-то «Александр Швец», а где-то «Швец А.», его
 * книги разъезжаются по разным местам списка. Год в скобках, а не через дефис, потому что дефис в
 * названиях встречается и сам («Core Java. Volume II — Advanced Features»), и обратно такое имя уже
 * не разобрать.
 *
 * <p>Имя не хранится, а вычисляется при выдаче. Значит, схему можно переиграть в любой момент — и
 * всё, что уже загружено, начнёт скачиваться по-новому само.
 */
@Service
public class CanonicalName {

    /** Символы, недопустимые в именах файлов Windows; в остальных системах они просто мешают. */
    private static final Pattern FORBIDDEN = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}]");

    /** Запас до предела пути Windows: у пользователя книга ляжет в папку с длинным именем. */
    private static final int MAX_LENGTH = 150;

    private static final int SEVERAL_AUTHORS = 2;

    public String forBook(BookCard book) {
        StringBuilder name = new StringBuilder();
        if (!book.authors().isEmpty()) {
            name.append(book.authors().get(0));
            if (book.authors().size() >= SEVERAL_AUTHORS) {
                name.append(" и др.");
            }
            name.append(" - ");
        }
        name.append(series(book)).append(title(book));
        if (book.year() != null) {
            name.append(" (").append(book.year()).append(')');
        }
        return trim(clean(name.toString())) + extension(book);
    }

    /**
     * Номер в серии пишется с ведущим нулём. Иначе десятая книга цикла встаёт между первой и второй
     * — сортировка имён посимвольная, и «10» идёт раньше «2».
     */
    private String series(BookCard book) {
        if (book.series() == null || book.series().isBlank()) {
            return "";
        }
        if (book.seriesNumber() == null) {
            return book.series() + " - ";
        }
        return "%s %02d - ".formatted(book.series(), book.seriesNumber());
    }

    private String title(BookCard book) {
        if (book.title() != null && !book.title().isBlank()) {
            return book.title();
        }
        // Названия нет — сохраняем под тем именем, под которым файл принесли: оно хотя бы
        // что-то говорит владельцу.
        String original = book.originalName();
        int dot = original.lastIndexOf('.');
        return dot > 0 ? original.substring(0, dot) : original;
    }

    private String extension(BookCard book) {
        return "." + book.format().name().toLowerCase(Locale.ROOT);
    }

    private String clean(String name) {
        return FORBIDDEN.matcher(name).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    /** Обрезка идёт по названию, а не по концу строки: год и расширение важнее хвоста названия. */
    private String trim(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int yearStart = name.lastIndexOf(" (");
        if (yearStart < 0) {
            return name.substring(0, MAX_LENGTH).trim();
        }
        String year = name.substring(yearStart);
        int room = MAX_LENGTH - year.length();
        return name.substring(0, Math.max(room, 0)).trim() + year;
    }
}
