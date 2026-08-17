package com.bookcase.enricher.messaging;

import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import com.bookcase.enricher.service.ProviderCache;
import com.bookcase.events.BookEnrichmentRequested;
import com.bookcase.events.Topics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Владелец просит уточнить книгу заново.
 *
 * <p>Закрытая задача не значит исчерпанная: справочник мог не знать книгу вчера и узнать сегодня,
 * мог ответить не тем, что нужно, мог быть недоступен ровно в те попытки, что ему отвели. Решает
 * это человек, глядя на карточку, — отсюда и просьба.
 *
 * <p>Вместе с задачей забывается и запомненный ответ справочника про эту книгу. Иначе повтор был бы
 * бессмысленным: ответ, включая отрицательный, лежит в памяти месяц, и наружу никто бы не пошёл.
 */
@Slf4j
@Component
public class EnrichmentRequestedListener {

    private final EnrichmentTaskRepository tasks;
    private final ProviderCache cache;

    public EnrichmentRequestedListener(EnrichmentTaskRepository tasks, ProviderCache cache) {
        this.tasks = tasks;
        this.cache = cache;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 5000),
            sameIntervalTopicReuseStrategy = SameIntervalTopicReuseStrategy.SINGLE_TOPIC,
            retryTopicSuffix = ".enricher.retry",
            dltTopicSuffix = Topics.ENRICHER_DLT_SUFFIX,
            autoCreateTopics = "false")
    @KafkaListener(topics = Topics.BOOK_ENRICHMENT_REQUESTED, groupId = "enricher")
    @Transactional
    public void onEnrichmentRequested(BookEnrichmentRequested event) {
        boolean queued =
                tasks.requeue(
                        event.bookId(),
                        event.ownerId(),
                        event.title(),
                        String.join(", ", event.authors()),
                        event.isbn(),
                        event.year());
        if (!queued) {
            // Задача и так ждёт своего часа. Сбрасывать ей счётчик попыток нельзя: частым
            // нажатием кнопки можно было бы ходить к справочнику без конца.
            log.info("книга {} уже стоит в очереди на уточнение", event.bookId());
            return;
        }
        cache.forget(
                new com.bookcase.enricher.client.Lookup(
                        event.title(), event.authors(), event.isbn(), event.year()));
        log.info("книга {} возвращена в очередь на уточнение по просьбе владельца", event.bookId());
    }

    @DltHandler
    public void onGaveUp(BookEnrichmentRequested event) {
        log.warn("просьбу уточнить книгу {} обработать не удалось", event.bookId());
    }
}
