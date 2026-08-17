package com.bookcase.catalog.service;

import com.bookcase.catalog.repository.BookRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Место, с которого продолжать выдачу.
 *
 * <p>Наружу отдаётся непрозрачной строкой намеренно: клиент не должен считать её номером страницы и
 * заниматься с ней арифметикой. Внутри — значение поля порядка и идентификатор последней показанной
 * книги.
 */
@Service
public class CursorCodec {

    private static final String SEPARATOR = " ";

    public String encode(String sortValue, UUID id) {
        String payload = sortValue + SEPARATOR + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /** Испорченная строка — не повод для отказа: выдача просто начинается сначала. */
    public Optional<BookRepository.Cursor> decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Optional.empty();
        }
        try {
            String payload =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = payload.split(SEPARATOR, 2);
            if (parts.length != 2) {
                return Optional.empty();
            }
            return Optional.of(new BookRepository.Cursor(parts[0], UUID.fromString(parts[1])));
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }
}
