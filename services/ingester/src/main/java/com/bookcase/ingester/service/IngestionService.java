package com.bookcase.ingester.service;

import com.bookcase.events.BookIngestionRequested;
import com.bookcase.ingester.client.StorageClient;
import com.bookcase.ingester.dto.IngestionRequest;
import com.bookcase.ingester.dto.IngestionTaskView;
import com.bookcase.ingester.exception.TaskNotFoundException;
import com.bookcase.ingester.messaging.EventPublisher;
import com.bookcase.ingester.repository.IngestionTaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Заведение задач разбора и ответы о их состоянии. */
@Slf4j
@Service
public class IngestionService {

    private static final int TASK_LIST_LIMIT = 100;

    private final IngestionTaskRepository tasks;
    private final StorageClient storage;
    private final EventPublisher events;

    public IngestionService(
            IngestionTaskRepository tasks, StorageClient storage, EventPublisher events) {
        this.tasks = tasks;
        this.storage = storage;
        this.events = events;
    }

    /**
     * Заводит задачу и просит разобрать файл.
     *
     * <p>Пропуск на скачивание берётся здесь, пока токен пользователя ещё на руках: разбирать будут
     * потом и в другом месте.
     */
    @Transactional
    public UUID request(String ownerId, String userToken, IngestionRequest request) {
        String ticket = storage.issueDownloadTicket(request.sha256(), userToken);
        UUID taskId = UUID.randomUUID();
        tasks.create(taskId, ownerId, request.sha256(), request.originalName(), ticket);
        events.ingestionRequested(
                new BookIngestionRequested(
                        UUID.randomUUID(),
                        taskId,
                        ownerId,
                        request.sha256(),
                        request.originalName(),
                        Instant.now()));
        log.info("заведена задача разбора {} для файла {}", taskId, request.sha256());
        return taskId;
    }

    public IngestionTaskView find(UUID taskId, String ownerId) {
        return tasks.find(taskId, ownerId)
                .orElseThrow(() -> new TaskNotFoundException(taskId.toString()));
    }

    public List<IngestionTaskView> findAll(String ownerId) {
        return tasks.findAll(ownerId, TASK_LIST_LIMIT);
    }
}
