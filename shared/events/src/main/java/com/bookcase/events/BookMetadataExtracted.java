package com.bookcase.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Файл разобран, метаданные извлечены и приведены к единому виду.
 *
 * @param eventId идентификатор события
 * @param taskId задача разбора
 * @param ownerId владелец
 * @param sha256 содержимое файла в хранилище
 * @param originalName имя, под которым файл загрузили
 * @param metadata что удалось собрать
 * @param occurredAt когда разбор завершился
 */
public record BookMetadataExtracted(
        UUID eventId,
        UUID taskId,
        String ownerId,
        String sha256,
        String originalName,
        BookMetadata metadata,
        Instant occurredAt) {}
