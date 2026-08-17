package com.bookcase.storage.messaging;

import com.bookcase.events.ExportCompleted;
import com.bookcase.events.ExportFailed;
import com.bookcase.events.ExportRequested;
import com.bookcase.events.Topics;
import com.bookcase.storage.dto.ArchiveLocation;
import com.bookcase.storage.exception.StoredFileNotFoundException;
import com.bookcase.storage.service.ExportService;
import com.bookcase.storage.service.FileStorageService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.stereotype.Component;

/**
 * Сборка архива по просьбе каталога.
 *
 * <p>Работа долгая: библиотека бывает на десятки гигабайт, и всё это время придётся читать файлы
 * один за другим. Поэтому повторы вынесены в отдельный топик — ждать в потребителе значило бы
 * остановить всю партицию из-за одной затянувшейся сборки.
 *
 * <p>Отсутствие файла — отказ окончательный: книгу могли удалить, пока архив стоял в очереди, и
 * повторять сборку бессмысленно. Такой отказ сразу становится ответом каталогу.
 */
@Slf4j
@Component
public class ExportListener {

    private final FileStorageService files;
    private final ExportService exports;
    private final EventPublisher events;

    public ExportListener(FileStorageService files, ExportService exports, EventPublisher events) {
        this.files = files;
        this.exports = exports;
        this.events = events;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 30000),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = Topics.STORAGE_RETRY_SUFFIX,
            dltTopicSuffix = Topics.STORAGE_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.EXPORT_REQUESTED, groupId = "storage")
    public void onExportRequested(ExportRequested request) {
        try {
            ArchiveLocation archive =
                    exports.export(
                            request.ownerId(),
                            request.taskId().toString(),
                            files.findAll(request.files(), request.ownerId()));
            events.exportCompleted(
                    new ExportCompleted(
                            UUID.randomUUID(),
                            request.taskId(),
                            request.ownerId(),
                            archive.key(),
                            archive.sizeBytes(),
                            Instant.now()));
        } catch (StoredFileNotFoundException _) {
            fail(request, "какой-то из файлов больше не принадлежит владельцу");
        } catch (IOException failure) {
            throw new UncheckedIOException("не удалось собрать архив", failure);
        }
    }

    @DltHandler
    public void onGaveUp(ExportRequested request) {
        fail(request, "собрать архив не удалось после нескольких попыток");
    }

    private void fail(ExportRequested request, String reason) {
        log.warn("выгрузка {} не удалась: {}", request.taskId(), reason);
        events.exportFailed(
                new ExportFailed(
                        UUID.randomUUID(),
                        request.taskId(),
                        request.ownerId(),
                        reason,
                        Instant.now()));
    }
}
