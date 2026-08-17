package com.bookcase.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Владелец просит уточнить книгу заново.
 *
 * <p>Обычное уточнение случается один раз: книга появилась — справочник ответил — задача закрыта.
 * Но закрытая задача не значит исчерпанная. Справочник мог не знать книгу вчера и узнать сегодня;
 * мог ответить, но не тем, что нужно; мог быть недоступен ровно в те несколько попыток, которые ему
 * отвели. Во всех этих случаях единственный, кто понимает, что стоит попробовать ещё раз, —
 * человек, который смотрит на карточку.
 *
 * <p>Событие несёт снимок карточки, а не только её номер: за прошедшее время владелец мог поправить
 * название или автора руками, и спрашивать справочник нужно уже о новом.
 *
 * @param eventId идентификатор события
 * @param bookId карточка
 * @param ownerId владелец
 * @param title название, каким оно сейчас в карточке
 * @param authors авторы
 * @param isbn ISBN
 * @param year год издания
 * @param occurredAt когда попросили
 */
public record BookEnrichmentRequested(
        UUID eventId,
        UUID bookId,
        String ownerId,
        String title,
        List<String> authors,
        String isbn,
        Integer year,
        Instant occurredAt) {

    public BookEnrichmentRequested {
        authors = authors == null ? List.of() : List.copyOf(authors);
    }
}
