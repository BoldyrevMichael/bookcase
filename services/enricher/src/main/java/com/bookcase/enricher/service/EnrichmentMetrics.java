package com.bookcase.enricher.service;

import com.bookcase.enricher.repository.EnrichmentTaskRepository;
import com.bookcase.enricher.service.state.EnrichmentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Показатель «сколько книг ждёт уточнения».
 *
 * <p>Он отвечает на единственный вопрос, который здесь возникает у наблюдателя: всё ли идёт своим
 * чередом или очередь встала. Растущее число ожидающих при исчерпанной квоте — обычное дело и
 * рассасывается назавтра; растущее число безнадёжных означает, что справочник недоступен всерьёз.
 *
 * <p>Значения снимаются по расписанию, а не при каждом обращении сборщика метрик: иначе частота
 * запросов к базе определялась бы настройками Prometheus, а не нашими.
 */
@Component
public class EnrichmentMetrics {

    private final EnrichmentTaskRepository tasks;
    private final MeterRegistry registry;
    private final Map<EnrichmentStatus, AtomicInteger> counters =
            new EnumMap<>(EnrichmentStatus.class);

    public EnrichmentMetrics(MeterRegistry registry, EnrichmentTaskRepository tasks) {
        this.tasks = tasks;
        this.registry = registry;
        for (EnrichmentStatus status : EnrichmentStatus.values()) {
            AtomicInteger counter = new AtomicInteger();
            counters.put(status, counter);
            Gauge.builder("bookcase.enrichment.tasks", counter, AtomicInteger::get)
                    .description("Задачи уточнения метаданных по состояниям")
                    .tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry);
        }
    }

    /**
     * Обращение к справочнику состоялось.
     *
     * <p>Считается по каждому справочнику и по исходу отдельно. Это то, чем меряется польза
     * уточнения: сколько раз спросили, сколько раз ответ пригодился, сколько раз пришлось
     * отвергнуть чужую книгу. Без разделения по исходам счётчик обращений говорит только о расходе
     * квоты и ничего — о том, стоил ли он того.
     */
    public void lookup(String provider, String outcome) {
        registry.counter("bookcase.enricher.lookups", "provider", provider, "outcome", outcome)
                .increment();
    }

    /**
     * Ответ нашёлся в памяти, наружу не ходили.
     *
     * <p>Доля попаданий — прямая мера того, сколько суточной квоты сэкономлено: каждый повтор
     * вопроса, отвеченный из памяти, это запрос, который остался для другой книги.
     */
    public void cacheHit(String provider) {
        registry.counter("bookcase.enricher.cache", "provider", provider, "outcome", "hit")
                .increment();
    }

    public void cacheMiss(String provider) {
        registry.counter("bookcase.enricher.cache", "provider", provider, "outcome", "miss")
                .increment();
    }

    @Scheduled(fixedDelayString = "${bookcase.enricher.metrics-interval:60s}")
    public void refresh() {
        Map<EnrichmentStatus, Integer> counts = tasks.countByStatus();
        counters.forEach((status, counter) -> counter.set(counts.getOrDefault(status, 0)));
    }
}
