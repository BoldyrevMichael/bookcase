package com.bookcase.storage.messaging;

import com.bookcase.events.ExportCompleted;
import com.bookcase.events.ExportFailed;
import com.bookcase.events.Topics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Ответы о собранных архивах. */
@Component
public class EventPublisher {

    private final KafkaTemplate<Object, Object> kafka;

    public EventPublisher(KafkaTemplate<Object, Object> kafka) {
        this.kafka = kafka;
    }

    public void exportCompleted(ExportCompleted event) {
        kafka.send(Topics.EXPORT_COMPLETED, event.taskId().toString(), event);
    }

    public void exportFailed(ExportFailed event) {
        kafka.send(Topics.EXPORT_FAILED, event.taskId().toString(), event);
    }
}
