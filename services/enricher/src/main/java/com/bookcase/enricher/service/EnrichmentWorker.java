package com.bookcase.enricher.service;

import com.bookcase.enricher.config.EnricherProperties;
import com.bookcase.enricher.dto.EnrichmentTask;
import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import com.bookcase.enricher.repository.ProviderResponseRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Фоновый работник: разбирает очередь уточнения.
 *
 * <p>Почему очередь, а не обработка прямо в потребителе события: спрашивать справочник приходится с
 * оглядкой на суточную квоту, и ждать своей очереди книга может часами. Держать это ожидание в
 * потребителе Kafka нельзя — он занимает партицию, и за одной книгой встают все следующие. Событие
 * приносит повод, расписание живёт в базе, а работник просто берёт то, чему пришёл срок.
 *
 * <p>Побочная выгода: если уточнитель полежал, задачи ждут в таблице, а не в позиции чтения топика,
 * и после запуска работа продолжается сама.
 */
@Slf4j
@Component
public class EnrichmentWorker {

    private final EnrichmentTaskRepository tasks;
    private final EnrichmentService enrichment;
    private final ProviderResponseRepository responses;
    private final EnricherProperties properties;

    public EnrichmentWorker(
            EnrichmentTaskRepository tasks,
            EnrichmentService enrichment,
            ProviderResponseRepository responses,
            EnricherProperties properties) {
        this.tasks = tasks;
        this.enrichment = enrichment;
        this.responses = responses;
        this.properties = properties;
    }

    /**
     * Берёт пачку задач, которым пришёл срок.
     *
     * <p>Пачка и её обработка идут в одной транзакции: задачи заперты до конца работы, поэтому
     * вторая копия сервиса возьмёт следующие, а не те же самые.
     */
    @Scheduled(fixedDelayString = "${bookcase.enricher.poll-interval}")
    @Transactional
    public void processDue() {
        List<EnrichmentTask> due = tasks.claimDue(properties.batchSize());
        if (due.isEmpty()) {
            return;
        }
        log.debug("к уточнению готово задач: {}", due.size());
        for (EnrichmentTask task : due) {
            enrichment.enrich(task);
        }
    }

    /**
     * Раз в сутки убирает залежавшиеся ответы: справочники пополняются, и «нет» может стать «да».
     */
    @Scheduled(cron = "${bookcase.enricher.cache-cleanup-cron:0 30 3 * * *}")
    public void forgetOldResponses() {
        int forgotten = responses.forget(properties.cacheTtl());
        if (forgotten > 0) {
            log.info("забыто устаревших ответов справочников: {}", forgotten);
        }
    }
}
