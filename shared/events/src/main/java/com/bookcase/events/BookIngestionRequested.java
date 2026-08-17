package com.bookcase.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Просьба разобрать загруженный файл.
 *
 * @param eventId идентификатор события; по нему потребитель узнаёт повтор
 * @param taskId задача разбора, по ней пользователь смотрит состояние
 * @param ownerId владелец
 * @param sha256 содержимое файла в хранилище
 * @param originalName имя, под которым файл загрузили
 * @param occurredAt когда просьба возникла
 */
public record BookIngestionRequested(
        UUID eventId,
        UUID taskId,
        String ownerId,
        String sha256,
        String originalName,
        Instant occurredAt) {}
