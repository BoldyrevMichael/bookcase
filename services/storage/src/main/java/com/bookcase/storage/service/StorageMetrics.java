package com.bookcase.storage.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Показатели хранилища.
 *
 * <p>Главное здесь — насколько часто срабатывает хранение одних и тех же байтов один раз. Это не
 * любопытство: дедупликация — единственная причина, по которой библиотека на тысячи книг занимает
 * меньше, чем сумма их размеров, и если доля повторов вдруг упала до нуля, значит что-то сломалось
 * в подсчёте хэша, а заметить это иначе нельзя — место просто начнёт кончаться.
 */
@Component
public class StorageMetrics {

    private final Counter stored;
    private final Counter deduplicated;
    private final DistributionSummary uploadSize;

    public StorageMetrics(MeterRegistry registry) {
        this.stored =
                Counter.builder("bookcase.storage.uploads")
                        .description("Принятые файлы")
                        .tag("outcome", "stored")
                        .register(registry);
        this.deduplicated =
                Counter.builder("bookcase.storage.uploads")
                        .description("Принятые файлы")
                        // Такие байты уже лежали: появилась только ссылка нового владельца.
                        .tag("outcome", "deduplicated")
                        .register(registry);
        this.uploadSize =
                DistributionSummary.builder("bookcase.storage.upload.size")
                        .description("Размер принятых файлов")
                        .baseUnit("bytes")
                        // Гистограмма, а не готовые доли: доли, посчитанные внутри процесса,
                        // нельзя сложить между экземплярами сервиса — среднее от двух 95-х
                        // долей не равно 95-й доле. Деления складываются, и Prometheus
                        // считает долю по ним сам.
                        .publishPercentileHistogram()
                        .minimumExpectedValue(64.0 * 1024)
                        .maximumExpectedValue(1024.0 * 1024 * 1024)
                        .register(registry);
    }

    public void accepted(long sizeBytes, boolean alreadyStored) {
        (alreadyStored ? deduplicated : stored).increment();
        uploadSize.record(sizeBytes);
    }
}
