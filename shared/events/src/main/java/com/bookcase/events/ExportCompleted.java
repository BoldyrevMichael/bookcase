package com.bookcase.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Архив собран.
 *
 * @param eventId идентификатор события
 * @param taskId задача выгрузки
 * @param ownerId владелец
 * @param archiveKey имя объекта с архивом
 * @param sizeBytes размер архива
 * @param occurredAt когда сборка завершилась
 */
public record ExportCompleted(
        UUID eventId,
        UUID taskId,
        String ownerId,
        String archiveKey,
        long sizeBytes,
        Instant occurredAt) {}
