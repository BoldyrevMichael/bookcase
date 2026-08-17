package com.bookcase.enricher.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки уточнения метаданных.
 *
 * @param google справочник Google Books
 * @param openLibrary справочник Open Library
 * @param pollInterval как часто фоновый работник заглядывает в очередь
 * @param batchSize сколько задач берётся за один заход
 * @param maxAttempts после скольких неудач задача признаётся безнадёжной
 * @param retryDelay пауза перед повторной попыткой; удваивается с каждой неудачей
 * @param cacheTtl сколько живёт запомненный ответ справочника
 * @param match правила сверки найденного с тем, что уже известно
 * @param storageUrl адрес хранилища: туда кладутся обложки
 * @param maxCoverSize предел размера скачиваемой обложки
 * @param connectTimeout сколько ждать соединения со справочником
 * @param readTimeout сколько ждать ответа. Без явных пределов недоступный справочник держит
 *     фонового работника столько, сколько сочтёт нужным операционная система, — а очередь в это
 *     время стоит
 */
@ConfigurationProperties("bookcase.enricher")
public record EnricherProperties(
        Google google,
        OpenLibrary openLibrary,
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10m") Duration retryDelay,
        @DefaultValue("30d") Duration cacheTtl,
        @DefaultValue Match match,
        @DefaultValue("http://localhost:8082") String storageUrl,
        @DefaultValue("5MB") org.springframework.util.unit.DataSize maxCoverSize,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout) {

    /**
     * Google Books.
     *
     * <p>Ключ обязателен по существу, а не по формальности: без него запросы уходят в общую
     * безымянную квоту, которая на практике всегда уже исчерпана — проверено, приходит 429. Поэтому
     * пустой ключ означает не «работать анонимно», а «этот справочник выключен».
     *
     * @param baseUrl адрес службы
     * @param apiKey ключ; пусто — справочник не используется
     * @param dailyQuota суточный предел бесплатного ключа
     */
    public record Google(
            @DefaultValue("https://www.googleapis.com/books/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("1000") int dailyQuota) {

        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * Open Library.
     *
     * @param baseUrl адрес службы
     * @param enabled можно выключить, не убирая настройки
     */
    public record OpenLibrary(
            @DefaultValue("https://openlibrary.org") String baseUrl,
            // Обложки живут на отдельной службе, поэтому и адрес отдельный. Вынесен в
            // настройки не ради гибкости, а чтобы проверка не ходила в настоящий интернет:
            // тест, зависящий от чужой доступности, однажды встанет вместе с ней.
            @DefaultValue("https://covers.openlibrary.org") String coversUrl,
            @DefaultValue("true") boolean enabled) {}

    /**
     * Насколько похожим должен быть кандидат, чтобы его приняли.
     *
     * <p>Пороги вынесены в настройки не ради гибкости, а потому что подбирались на живом корпусе и
     * будут подбираться ещё: цена ошибки здесь несимметрична. Пропущенная книга останется как есть,
     * а принятый чужой кандидат подменит название, и владелец этого не заметит.
     *
     * @param minCoverage какая доля значимых слов названия должна совпасть
     * @param coverageWithoutAuthor тот же порог, когда автора для сверки нет
     * @param maxYearGap на сколько лет издание может расходиться, чтобы считаться тем же
     */
    public record Match(
            @DefaultValue("0.5") double minCoverage,
            @DefaultValue("0.8") double coverageWithoutAuthor,
            @DefaultValue("30") int maxYearGap) {}
}
