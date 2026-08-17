package com.bookcase.catalog.messaging;

import com.bookcase.catalog.repository.ExportTaskRepository;
import com.bookcase.events.ExportCompleted;
import com.bookcase.events.ExportFailed;
import com.bookcase.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Ответ хранилища о собранном архиве.
 *
 * <p>Задача не должна остаться навсегда в состоянии «собирается»: человек ждёт ответа, пусть и
 * отрицательного.
 */
@Slf4j
@Component
public class ExportListener {

    private final ExportTaskRepository tasks;

    public ExportListener(ExportTaskRepository tasks) {
        this.tasks = tasks;
    }

    @KafkaListener(topics = Topics.EXPORT_COMPLETED, groupId = "catalog")
    public void onCompleted(ExportCompleted event) {
        log.info("архив по задаче {} собран: {} байт", event.taskId(), event.sizeBytes());
        tasks.markSucceeded(event.taskId(), event.archiveKey(), event.sizeBytes());
    }

    @KafkaListener(topics = Topics.EXPORT_FAILED, groupId = "catalog")
    public void onFailed(ExportFailed event) {
        log.warn("архив по задаче {} собрать не удалось: {}", event.taskId(), event.reason());
        tasks.markFailed(event.taskId(), event.reason());
    }
}
