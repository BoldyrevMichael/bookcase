package com.bookcase.enricher.service;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.CoverDownloader;
import com.bookcase.enricher.client.StorageClient;
import com.bookcase.enricher.dto.EnrichmentTask;
import com.bookcase.enricher.messaging.EventPublisher;
import com.bookcase.events.BookEnriched;
import com.bookcase.metadata.LanguageNormalizer;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Превращение принятого кандидата в находку для каталога.
 *
 * <p>Отделено от самого уточнения намеренно: там решается, о той ли книге рассказал справочник, а
 * здесь — как оформить ответ. Сюда же собрано всё, что нужно сделать с ответом перед отправкой:
 * привести язык к общему виду и забрать обложку.
 */
@Slf4j
@Component
public class FindingPublisher {

    private final EventPublisher events;
    private final LanguageNormalizer languages;
    private final CoverDownloader coverDownloader;
    private final StorageClient storage;

    public FindingPublisher(
            EventPublisher events,
            LanguageNormalizer languages,
            CoverDownloader coverDownloader,
            StorageClient storage) {
        this.events = events;
        this.languages = languages;
        this.coverDownloader = coverDownloader;
        this.storage = storage;
    }

    /** Отправляет находку каталогу. Что из неё записать, решает он. */
    public void publish(EnrichmentTask task, Candidate candidate) {
        events.bookEnriched(
                new BookEnriched(
                        UUID.randomUUID(),
                        task.bookId(),
                        task.ownerId(),
                        candidate.provider(),
                        candidate.title(),
                        candidate.authors(),
                        candidate.year(),
                        // Справочники пишут язык по-разному: Open Library отдаёт «eng»,
                        // Google Books — «en». В карточке он должен быть один, иначе отбор
                        // по языку раздваивается на два одинаковых значения.
                        languages.normalize(candidate.language()),
                        candidate.isbn(),
                        candidate.publisher(),
                        candidate.themes(),
                        cover(task, candidate),
                        Instant.now()));
    }

    /**
     * Забирает обложку и укладывает её в хранилище.
     *
     * <p>Обложка — дополнение, а не условие: если её не отдали или хранилище не приняло, карточка
     * всё равно получит название и автора. Поэтому здесь нет ни повторов, ни отказа задачи.
     */
    private String cover(EnrichmentTask task, Candidate candidate) {
        return coverDownloader
                .download(candidate.coverUrl())
                .flatMap(
                        image ->
                                storage.storeCover(
                                        image.content(),
                                        image.contentType(),
                                        task.bookId(),
                                        task.ownerId()))
                .orElse(null);
    }
}
