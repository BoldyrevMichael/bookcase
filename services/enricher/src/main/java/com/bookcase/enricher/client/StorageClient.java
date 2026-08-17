package com.bookcase.enricher.client;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Передача обложки в хранилище.
 *
 * <p>Токен подставляет перехватчик: у сервиса своя учётная запись, и право у неё одно — класть
 * обложки. Хранилище само решит, новая это картинка или такая уже есть: имя объекта — хэш
 * содержимого, поэтому одна обложка на десять книг хранится однажды.
 */
@Slf4j
@Component
public class StorageClient {

    private final RestClient client;

    public StorageClient(RestClient storageRestClient) {
        this.client = storageRestClient;
    }

    /**
     * Отпускает обложку удалённой книги.
     *
     * <p>Обычно это делает каталог сразу при удалении, своим токеном. Сюда доходят случаи, когда
     * обложка появилась уже после: справочник отвечал долго, а книгу успели убрать. Право на это
     * даёт та же роль, что и на загрузку: кто положил, тот и уберёт.
     *
     * <p>Неудача не проглатывается: пусть известие об удалении вернётся повтором. Проглоченная
     * ошибка означала бы картинку, которую уже некому убрать, — задача-то к тому времени снята.
     */
    public void releaseCover(UUID bookId) {
        client.delete()
                .uri("/api/v1/covers/holders/{bookId}", bookId)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Кладёт обложку и возвращает её хэш.
     *
     * <p>Держатель — карточка книги: хранилище ведёт список тех, кто показывает обложку, и убирает
     * её, когда список пустеет. Без этого обложка пережила бы книгу и осталась навсегда.
     *
     * <p>Неудача не отменяет уточнения: карточка получит название и автора, просто без картинки.
     */
    public Optional<String> storeCover(
            byte[] content, String contentType, UUID bookId, String ownerId) {
        try {
            Map<?, ?> response =
                    client.post()
                            .uri(
                                    uri ->
                                            uri.path("/api/v1/covers")
                                                    .queryParam("holder", bookId)
                                                    .queryParam("owner", ownerId)
                                                    .build())
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(content)
                            .retrieve()
                            .body(Map.class);
            if (response == null || response.get("sha256") == null) {
                return Optional.empty();
            }
            return Optional.of(String.valueOf(response.get("sha256")));
        } catch (RestClientException e) {
            log.warn("обложку не удалось передать хранилищу: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
