package com.bookcase.storage.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки хранилища.
 *
 * @param s3 доступ к объектному хранилищу
 * @param booksBucket корзина с оригиналами книг
 * @param exportsBucket корзина с собранными архивами
 * @param coversBucket корзина с обложками
 * @param maxCoverSize предел размера обложки: картинка, а не книга
 * @param downloadTokenSecret ключ для подписи ссылок на скачивание
 * @param downloadTokenTtl срок жизни такой ссылки
 */
@ConfigurationProperties("bookcase.storage")
public record StorageProperties(
        S3 s3,
        @DefaultValue("books") String booksBucket,
        @DefaultValue("exports") String exportsBucket,
        @DefaultValue("covers") String coversBucket,
        @DefaultValue("5MB") org.springframework.util.unit.DataSize maxCoverSize,
        String downloadTokenSecret,
        @DefaultValue("1m") Duration downloadTokenTtl) {

    /**
     * Доступ к объектному хранилищу.
     *
     * @param endpoint адрес шлюза S3
     * @param region область; у совместимых хранилищ значение произвольное, но должно совпадать с
     *     тем, что ожидает сервер
     * @param accessKey идентификатор ключа
     * @param secretKey сам ключ
     */
    public record S3(
            String endpoint,
            @DefaultValue("us-east-1") String region,
            String accessKey,
            String secretKey) {}
}
