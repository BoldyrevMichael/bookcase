package com.bookcase.catalog.dto;

import com.bookcase.catalog.service.state.ExportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Задача выгрузки библиотеки одним архивом.
 *
 * @param id задача
 * @param status состояние
 * @param bookCount сколько книг просили выгрузить
 * @param sizeBytes размер готового архива
 * @param failureReason причина отказа
 * @param downloadUrl ссылка на готовый архив; действует ограниченное время
 * @param createdAt когда задача заведена
 * @param updatedAt когда состояние менялось
 */
public record ExportTaskView(
        UUID id,
        ExportStatus status,
        int bookCount,
        Long sizeBytes,
        String failureReason,
        String downloadUrl,
        Instant createdAt,
        Instant updatedAt) {}
