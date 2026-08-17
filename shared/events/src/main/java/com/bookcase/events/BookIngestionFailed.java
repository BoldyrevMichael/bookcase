package com.bookcase.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Разобрать файл не удалось.
 *
 * @param eventId идентификатор события
 * @param taskId задача разбора
 * @param ownerId владелец
 * @param sha256 содержимое файла в хранилище
 * @param reason причина отказа словами, которые можно показать человеку
 * @param permanent отказ окончательный: повторять бессмысленно
 * @param occurredAt когда стало ясно
 */
public record BookIngestionFailed(
        UUID eventId,
        UUID taskId,
        String ownerId,
        String sha256,
        String reason,
        boolean permanent,
        Instant occurredAt) {}
