package com.bookcase.enricher.client;

import com.bookcase.enricher.config.EnricherProperties;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Скачивание обложки по адресу, который дал справочник.
 *
 * <p>Обложки лежат не там же, где метаданные: у Google Books это отдельная служба картинок, у Open
 * Library — свой домен обложек. Поэтому адрес здесь абсолютный и приходит из ответа справочника.
 *
 * <p>Читается в память с пределом: содержимое чужое, и полагаться на заявленный размер нельзя.
 * Обложка весит сотни килобайт, всё, что заметно больше, — не обложка.
 */
@Slf4j
@Component
public class CoverDownloader {

    /**
     * Меньше этого обложек не бывает.
     *
     * <p>Порог нужен против пустышек: Open Library на книгу без обложки отвечает картинкой в девять
     * байт, и она успешно проходит все проверки «пришла ли картинка».
     */
    private static final int MIN_SIZE = 1024;

    private final RestClient client;
    private final long maxSize;

    public CoverDownloader(
            EnricherProperties properties,
            org.springframework.http.client.ClientHttpRequestFactory requestFactory) {
        this.maxSize = properties.maxCoverSize().toBytes();
        // Те же пределы ожидания, что и у справочников: картинка приходит с той же
        // чужой стороны и точно так же может не прийти вовсе.
        this.client = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Содержимое обложки вместе с типом картинки.
     *
     * <p>Сравнение и вывод написаны руками: у записи с массивом они достались бы от ссылки — две
     * одинаковые картинки считались бы разными, а в журнал уехал бы адрес массива. Заодно {@code
     * toString} печатает размер, а не сотни килобайт двоичных данных.
     */
    public record Cover(byte[] content, String contentType) {

        public Cover {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        // Сопоставление с образцом записи (Cover(byte[] content, ...)) здесь не годится:
        // оно идёт через метод доступа, а тот отдаёт копию массива — сравнение двух обложек
        // копировало бы сотни килобайт.
        @SuppressWarnings("java:S6878")
        @Override
        public boolean equals(Object other) {
            return other instanceof Cover cover
                    && Arrays.equals(content, cover.content)
                    && Objects.equals(contentType, cover.contentType);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(content) + Objects.hashCode(contentType);
        }

        @Override
        public String toString() {
            return "Cover[%d байт, %s]".formatted(content.length, contentType);
        }
    }

    /**
     * Забирает картинку.
     *
     * <p>Неудача здесь ничего не отменяет: книга уже уточнена, обложка — приятное дополнение.
     * Поэтому наружу отдаётся пусто, а не исключение.
     */
    public Optional<Cover> download(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            var response = client.get().uri(url).retrieve().toEntity(byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length < MIN_SIZE) {
                log.info(
                        "по адресу {} пришла не обложка, а {} байт",
                        url,
                        body == null ? 0 : body.length);
                return Optional.empty();
            }
            if (body.length > maxSize) {
                log.info("обложка по адресу {} велика для обложки: {} байт", url, body.length);
                return Optional.empty();
            }
            MediaType type = response.getHeaders().getContentType();
            if (type == null || !"image".equals(type.getType())) {
                log.info("по адресу {} пришла не картинка: {}", url, type);
                return Optional.empty();
            }
            return Optional.of(new Cover(body, type.toString()));
        } catch (RestClientException e) {
            log.info("обложку по адресу {} забрать не вышло: {}", url, e.getMessage());
            return Optional.empty();
        }
    }
}
