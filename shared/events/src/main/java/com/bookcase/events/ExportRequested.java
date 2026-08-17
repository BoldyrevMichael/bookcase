package com.bookcase.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Просьба собрать архив из перечисленных файлов.
 *
 * @param eventId идентификатор события
 * @param taskId задача выгрузки
 * @param ownerId владелец
 * @param files содержимое файлов, которые должны попасть в архив
 * @param occurredAt когда просьба возникла
 */
public record ExportRequested(
        UUID eventId, UUID taskId, String ownerId, List<String> files, Instant occurredAt) {

    public ExportRequested {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
