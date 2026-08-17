package com.bookcase.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Собрать архив не удалось.
 *
 * @param eventId идентификатор события
 * @param taskId задача выгрузки
 * @param ownerId владелец
 * @param reason причина словами, которые можно показать человеку
 * @param occurredAt когда стало ясно
 */
public record ExportFailed(
        UUID eventId, UUID taskId, String ownerId, String reason, Instant occurredAt) {}
