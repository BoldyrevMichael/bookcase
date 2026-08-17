package com.bookcase.catalog.service;

import com.bookcase.catalog.client.StorageClient;
import com.bookcase.catalog.config.CatalogProperties;
import com.bookcase.catalog.dto.ExportTaskView;
import com.bookcase.catalog.exception.ExportNotFoundException;
import com.bookcase.catalog.messaging.EventPublisher;
import com.bookcase.catalog.repository.ExportTaskRepository;
import com.bookcase.catalog.service.state.ExportStatus;
import com.bookcase.events.ExportRequested;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Выгрузка библиотеки одним архивом.
 *
 * <p>Каталог знает, какие книги у владельца, но не держит их байтов, поэтому собирает архив
 * хранилище. Ждать с открытым соединением тут некому: библиотека бывает на десятки гигабайт, —
 * поэтому заводится задача, а ответ приходит событием.
 */
@Slf4j
@Service
public class ExportService {

    private final ExportTaskRepository tasks;
    private final BookService books;
    private final EventPublisher events;
    private final StorageClient storage;
    private final CatalogProperties properties;

    public ExportService(
            ExportTaskRepository tasks,
            BookService books,
            EventPublisher events,
            StorageClient storage,
            CatalogProperties properties) {
        this.tasks = tasks;
        this.books = books;
        this.events = events;
        this.storage = storage;
        this.properties = properties;
    }

    @Transactional
    public UUID request(String ownerId) {
        List<String> files = books.allFiles(ownerId);
        UUID taskId = UUID.randomUUID();
        tasks.create(taskId, ownerId, files.size());
        events.exportRequested(
                new ExportRequested(UUID.randomUUID(), taskId, ownerId, files, Instant.now()));
        log.info("заведена задача выгрузки {} на {} книг", taskId, files.size());
        return taskId;
    }

    /**
     * Состояние задачи. У готовой выдаётся ссылка на архив — короткоживущая и подписанная, потому
     * что открывать её будет браузер, а заголовок с токеном он к ссылке не приложит.
     */
    public ExportTaskView find(UUID taskId, String ownerId, String userToken) {
        ExportTaskRepository.Task task =
                tasks.find(taskId, ownerId)
                        .orElseThrow(() -> new ExportNotFoundException(taskId.toString()));

        String downloadUrl = null;
        if (task.status() == ExportStatus.SUCCEEDED && task.archiveKey() != null) {
            StorageClient.Ticket ticket = storage.issueArchiveTicket(taskId.toString(), userToken);
            downloadUrl =
                    "%s/api/v1/archives/%s/download/%s"
                            .formatted(properties.storagePublicUrl(), taskId, ticket.token());
        }
        return new ExportTaskView(
                task.id(),
                task.status(),
                task.bookCount(),
                task.sizeBytes(),
                task.failureReason(),
                downloadUrl,
                task.createdAt(),
                task.updatedAt());
    }
}
