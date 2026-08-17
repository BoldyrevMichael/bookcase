package com.bookcase.catalog.service;

import com.bookcase.catalog.repository.BookRepository;
import com.bookcase.catalog.service.state.BookStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Размер библиотеки по состояниям карточек.
 *
 * <p>Показатель отвечает на единственный вопрос, который задают о библиотеке со стороны: сколько в
 * ней книг и сколько из них требуют внимания человека. Растущее число требующих просмотра — это не
 * поломка, а сигнал: либо в загрузке пошли файлы без метаданных, либо перестало работать уточнение.
 *
 * <p>Значения снимаются по расписанию, а не при каждом обращении сборщика: иначе частота запросов к
 * базе определялась бы настройками наблюдения, а не нашими.
 */
@Component
public class LibraryMetrics {

    private final BookRepository books;
    private final Map<BookStatus, AtomicInteger> counters = new EnumMap<>(BookStatus.class);

    public LibraryMetrics(MeterRegistry registry, BookRepository books) {
        this.books = books;
        for (BookStatus status : BookStatus.values()) {
            AtomicInteger counter = new AtomicInteger();
            counters.put(status, counter);
            Gauge.builder("bookcase.books", counter, AtomicInteger::get)
                    .description("Карточки книг по состояниям")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
    }

    @Scheduled(fixedDelayString = "${bookcase.catalog.metrics-interval:60s}")
    public void refresh() {
        Map<BookStatus, Integer> counts = books.countByStatus();
        counters.forEach((status, counter) -> counter.set(counts.getOrDefault(status, 0)));
    }
}
