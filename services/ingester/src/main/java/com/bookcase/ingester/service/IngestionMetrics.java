package com.bookcase.ingester.service;

import com.bookcase.events.BookFormat;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Показатели разбора.
 *
 * <p>Меряется то, о чём приходится гадать, когда что-то идёт не так: сколько занимает разбор
 * каждого формата, часто ли он заканчивается неполной карточкой и сколько файлов отвергается.
 *
 * <p>Время разделено по форматам намеренно. PDF на сотни страниц со сканами и небольшой FB2
 * отличаются на порядок, и в общем среднем это различие исчезает: получается число, по которому
 * нельзя сказать ни что нормально, ни что уже плохо.
 */
@Component
public class IngestionMetrics {

    private static final String PARSE_TIMER = "bookcase.ingestion.parse";

    private final MeterRegistry registry;

    public IngestionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Файл разобран.
     *
     * @param complete собралась ли карточка целиком. Неполная — не поломка: у скана метаданных нет
     *     вовсе. Но если доля неполных вдруг выросла, это повод посмотреть, что изменилось.
     */
    public void parsed(BookFormat format, long startedAtNanos, boolean complete) {
        timer(format.name().toLowerCase(Locale.ROOT), complete ? "complete" : "incomplete")
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    /** Файл отвергнут: не книга, не тот формат, повреждён. */
    public void rejected(long startedAtNanos) {
        timer("unknown", "rejected")
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }

    private Timer timer(String format, String outcome) {
        return Timer.builder(PARSE_TIMER)
                .description("Время разбора книги от получения файла до готовых метаданных")
                .tag("format", format)
                .tag("outcome", outcome)
                // Доли, а не среднее: среднее время разбора ничего не говорит о том, сколько
                // ждёт самый неудачливый файл, а именно он и вызывает жалобы. Считает их
                // Prometheus по делениям гистограммы, а не сам сервис: доли, посчитанные
                // внутри процесса, нельзя сложить между экземплярами — среднее от двух
                // 95-х долей не равно 95-й доле. Заодно к делениям прикрепляются примеры
                // с идентификатором трассы: из всплеска на графике видно, какой именно
                // разбор был долгим.
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofMinutes(2))
                .register(registry);
    }
}
