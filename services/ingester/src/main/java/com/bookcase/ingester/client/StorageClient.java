package com.bookcase.ingester.client;

import com.bookcase.ingester.config.IngesterProperties;
import com.bookcase.ingester.exception.UnsupportedContentException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Обращения к сервису файлов.
 *
 * <p>Файл нужен разбору, а разбор идёт в фоне, когда токена пользователя уже нет. Поэтому в момент
 * заведения задачи, пока токен ещё есть, у хранилища запрашивается подписанный пропуск — он выписан
 * на один файл одного владельца и на ограниченный срок, и с ним разбор потом заберёт содержимое.
 *
 * <p>Другой возможный путь — своя учётная запись у сервиса и право читать что угодно — требует
 * доверять сервису больше, чем нужно для его работы; пропуск такого права не даёт.
 */
@Component
public class StorageClient {

    private static final int COPY_BUFFER = 64 * 1024;

    private final RestClient client;
    private final long maxFileSize;

    public StorageClient(IngesterProperties properties) {
        // Строитель RestClient в Boot 4 сам по себе не создаётся, поэтому клиент собирается здесь.
        this.client = RestClient.create(properties.storageUrl());
        this.maxFileSize = properties.maxFileSize().toBytes();
    }

    /** Просит пропуск на скачивание от имени пользователя, чей токен пришёл с запросом. */
    public String issueDownloadTicket(String sha256, String userToken) {
        Map<?, ?> response =
                client.post()
                        .uri("/api/v1/files/{sha256}/ticket", sha256)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .retrieve()
                        .body(Map.class);
        if (response == null || response.get("token") == null) {
            throw new IllegalStateException("хранилище не выдало пропуск на скачивание");
        }
        return String.valueOf(response.get("token"));
    }

    /**
     * Забирает содержимое во временный файл.
     *
     * <p>Размер ограничен: в библиотеке попадаются архивы с исходными материалами к книге на сотни
     * мегабайт, разбирать которые бессмысленно. Предел проверяется по ходу чтения, а не по
     * заявленному размеру, — заявленному верить незачем.
     */
    public void download(String sha256, String ticket, Path target) {
        client.get()
                .uri("/api/v1/files/{sha256}/download/{ticket}", sha256, ticket)
                .exchange(
                        (request, response) -> {
                            // Ответ разбирается вручную, поэтому и состояние проверяется вручную:
                            // без этой проверки отказ хранилища молча превратился бы в пустой файл,
                            // а тот — в жалобу «формат не опознан», то есть не на то.
                            if (!response.getStatusCode().is2xxSuccessful()) {
                                throw new IllegalStateException(
                                        "хранилище не отдало файл "
                                                + sha256
                                                + ": "
                                                + response.getStatusCode());
                            }
                            try (InputStream body = response.getBody();
                                    OutputStream file = Files.newOutputStream(target)) {
                                copyWithLimit(body, file);
                            }
                            return null;
                        });
    }

    private void copyWithLimit(InputStream source, OutputStream target) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER];
        long copied = 0;
        int read;
        while ((read = source.read(buffer)) != -1) {
            copied += read;
            if (copied > maxFileSize) {
                throw new UnsupportedContentException(
                        "файл больше "
                                + maxFileSize / (1024 * 1024)
                                + " МБ — для книги это слишком много");
            }
            target.write(buffer, 0, read);
        }
    }
}
