package com.bookcase.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Внешний справочник рассказал о книге что-то, чего не было в файле.
 *
 * <p>Событие несёт найденное целиком, а не разницу с карточкой: решение, что из этого записать,
 * принимает каталог. Только он знает, откуда взялось каждое поле — исправленное человеком не
 * отменяется никаким справочником, а вычитанное из самого файла надёжнее найденного по названию.
 * Если бы отбор делал уточнитель, ему пришлось бы держать у себя копию этого знания, и две копии
 * рано или поздно разошлись бы.
 *
 * <p>Год и издательство здесь — это данные того издания, которое нашлось у справочника. Оно нередко
 * новее того, что лежит у владельца на диске: и Google Books, и Open Library охотно отдают
 * последнее переиздание. Поэтому каталог применяет их только к пустым полям.
 *
 * @param eventId идентификатор события
 * @param bookId карточка, к которой относится находка
 * @param ownerId владелец
 * @param provider кто ответил: имя справочника попадает в карточку и видно, откуда данные
 * @param title название
 * @param authors авторы
 * @param year год издания
 * @param language язык двухбуквенным кодом
 * @param isbn ISBN в виде ISBN-13
 * @param publisher издательство
 * @param themes темы, как их называет справочник
 * @param coverSha256 обложка, уже уложенная в хранилище; пусто, если её не было
 * @param occurredAt когда справочник ответил
 */
public record BookEnriched(
        UUID eventId,
        UUID bookId,
        String ownerId,
        String provider,
        String title,
        List<String> authors,
        Integer year,
        String language,
        String isbn,
        String publisher,
        List<String> themes,
        String coverSha256,
        Instant occurredAt) {

    public BookEnriched {
        authors = authors == null ? List.of() : List.copyOf(authors);
        themes = themes == null ? List.of() : List.copyOf(themes);
    }
}
