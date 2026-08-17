package com.bookcase.ingester.dto;

import com.bookcase.events.BookMetadata;
import com.bookcase.ingester.service.state.IngestionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Состояние задачи разбора — то, что показывается пользователю.
 *
 * @param id задача
 * @param sha256 разбираемый файл
 * @param originalName имя, под которым файл загрузили
 * @param status состояние
 * @param failureReason причина отказа, если разбор не удался
 * @param metadata что удалось собрать, если удался
 * @param createdAt когда задача заведена
 * @param updatedAt когда состояние менялось в последний раз
 */
public record IngestionTaskView(
        UUID id,
        String sha256,
        String originalName,
        IngestionStatus status,
        String failureReason,
        BookMetadata metadata,
        Instant createdAt,
        Instant updatedAt) {}
