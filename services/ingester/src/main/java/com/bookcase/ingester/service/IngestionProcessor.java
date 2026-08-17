package com.bookcase.ingester.service;

import com.bookcase.events.BookIngestionFailed;
import com.bookcase.events.BookIngestionRequested;
import com.bookcase.events.BookMetadata;
import com.bookcase.events.BookMetadataExtracted;
import com.bookcase.ingester.client.StorageClient;
import com.bookcase.ingester.exception.UnsupportedContentException;
import com.bookcase.ingester.messaging.EventPublisher;
import com.bookcase.ingester.repository.IngestionTaskRepository;
import com.bookcase.ingester.repository.ProcessedEventRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Разбор файла: от просьбы до готовых метаданных.
 *
 * <p>Неудачи здесь двух разных сортов, и обходятся с ними по-разному. Испорченный файл, документ
 * Word вместо книги, слишком большой архив — это отказ окончательный: повторять его бессмысленно,
 * задача сразу получает причину, которую можно показать человеку. Недоступное хранилище или
 * оборванное соединение — дело временное, и такая неудача выпускается наружу, чтобы очередь
 * повторила попытку.
 */
@Slf4j
@Service
public class IngestionProcessor {

    private final IngestionTaskRepository tasks;
    private final ProcessedEventRepository processedEvents;
    private final StorageClient storage;
    private final MetadataExtractor extractor;
    private final EventPublisher events;
    private final IngestionMetrics metrics;

    public IngestionProcessor(
            IngestionTaskRepository tasks,
            ProcessedEventRepository processedEvents,
            StorageClient storage,
            MetadataExtractor extractor,
            EventPublisher events,
            IngestionMetrics metrics) {
        this.tasks = tasks;
        this.processedEvents = processedEvents;
        this.storage = storage;
        this.extractor = extractor;
        this.events = events;
        this.metrics = metrics;
    }

    public void process(BookIngestionRequested request) {
        if (processedEvents.alreadyProcessed(request.eventId())) {
            log.info("событие {} уже обработано, повтор пропущен", request.eventId());
            return;
        }
        tasks.markRunning(request.taskId());

        Path file = createTempFile();
        // Время разбора считается по форматам отдельно: PDF на сотни страниц и небольшой
        // FB2 — это разные величины, и в общем среднем они друг друга скрывают.
        long startedAt = System.nanoTime();
        try {
            storage.download(request.sha256(), ticketFor(request.taskId()), file);
            BookMetadata metadata = extractor.extract(file, request.originalName());
            metrics.parsed(metadata.format(), startedAt, metadata.isComplete());
            tasks.markSucceeded(request.taskId(), metadata);
            events.metadataExtracted(
                    new BookMetadataExtracted(
                            UUID.randomUUID(),
                            request.taskId(),
                            request.ownerId(),
                            request.sha256(),
                            request.originalName(),
                            metadata,
                            Instant.now()));
            processedEvents.markProcessed(request.eventId());
            log.info("файл {} разобран как {}", request.sha256(), metadata.format());
        } catch (UnsupportedContentException permanent) {
            metrics.rejected(startedAt);
            fail(request, permanent.getMessage());
        } finally {
            deleteQuietly(file);
        }
    }

    /** Разбор не удался окончательно: повторять нечего, задача закрывается с причиной. */
    public void fail(BookIngestionRequested request, String reason) {
        log.info("файл {} разобрать не удалось: {}", request.sha256(), reason);
        tasks.markFailed(request.taskId(), reason);
        events.ingestionFailed(
                new BookIngestionFailed(
                        UUID.randomUUID(),
                        request.taskId(),
                        request.ownerId(),
                        request.sha256(),
                        reason,
                        true,
                        Instant.now()));
        processedEvents.markProcessed(request.eventId());
    }

    private String ticketFor(UUID taskId) {
        return tasks.findDownloadTicket(taskId)
                .orElseThrow(
                        () -> new IllegalStateException("у задачи " + taskId + " нет пропуска"));
    }

    private Path createTempFile() {
        try {
            return Files.createTempFile("bookcase-ingest-", ".bin");
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось создать временный файл", exception);
        }
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("не удалось удалить временный файл {}", file, exception);
        }
    }
}
