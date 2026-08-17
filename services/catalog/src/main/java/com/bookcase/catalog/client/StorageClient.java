package com.bookcase.catalog.client;

import com.bookcase.catalog.config.CatalogProperties;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Обращения к сервису файлов.
 *
 * <p>Каталог не хранит байты и не решает судьбу объектов: он лишь просит пропуск на скачивание и
 * сообщает, что владелец расстался с книгой. Удалять ли сам объект, решает хранилище — те же байты
 * могут лежать у другого владельца.
 */
@Component
public class StorageClient {

    private final RestClient client;

    public StorageClient(CatalogProperties properties) {
        this.client = RestClient.create(properties.storageUrl());
    }

    /** Пропуск на скачивание книги — от имени пользователя, чей токен пришёл с запросом. */
    public Ticket issueBookTicket(String sha256, String userToken) {
        return read(
                client.post()
                        .uri("/api/v1/files/{sha256}/ticket", sha256)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .retrieve()
                        .body(Map.class));
    }

    /** Пропуск на скачивание собранного архива. */
    public Ticket issueArchiveTicket(String exportId, String userToken) {
        return read(
                client.post()
                        .uri("/api/v1/archives/{exportId}/ticket", exportId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .retrieve()
                        .body(Map.class));
    }

    /**
     * Забирает обложку.
     *
     * <p>Каталог проводит её через себя, а не отдаёт наружу адрес в хранилище. Адрес обложки — это
     * хэш её содержимого, и всякий, кто его знает, может проверить, лежит ли такая книга в
     * библиотеке: ту же картинку нетрудно скачать у справочника и посчитать хэш самому. Проводя
     * запрос через карточку, каталог сначала убеждается, что книга принадлежит спрашивающему.
     */
    public byte[] fetchCover(String sha256, String userToken) {
        return client.get()
                .uri("/api/v1/covers/{sha256}", sha256)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .retrieve()
                .body(byte[].class);
    }

    /** Сообщает, что карточки больше нет и обложка ей не нужна. */
    public void releaseCover(java.util.UUID bookId, String userToken) {
        client.delete()
                .uri("/api/v1/covers/holders/{bookId}", bookId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .retrieve()
                .toBodilessEntity();
    }

    /** Сообщает, что владелец больше не держит этот файл. */
    public void releaseFile(String sha256, String userToken) {
        client.delete()
                .uri("/api/v1/files/{sha256}", sha256)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .retrieve()
                .toBodilessEntity();
    }

    private Ticket read(Map<?, ?> response) {
        if (response == null || response.get("token") == null) {
            throw new IllegalStateException("хранилище не выдало пропуск на скачивание");
        }
        return new Ticket(
                String.valueOf(response.get("token")), String.valueOf(response.get("expiresAt")));
    }

    /**
     * Пропуск на скачивание.
     *
     * @param token сам пропуск
     * @param expiresAt до какого момента он действует
     */
    public record Ticket(String token, String expiresAt) {}
}
