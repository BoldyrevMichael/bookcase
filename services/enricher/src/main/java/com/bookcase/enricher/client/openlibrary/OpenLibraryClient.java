package com.bookcase.enricher.client.openlibrary;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.client.MetadataProvider;
import com.bookcase.enricher.config.EnricherProperties;
import com.bookcase.enricher.service.SearchTerms;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Справочник Open Library.
 *
 * <p>Спрашивается вторым и не как замена на случай отказа, а как другой источник: на корпусе он
 * нашёл то, о чём Google Books молчит, — «Partitura Vtoroĭ mirovoĭ» Нарочницкой и «Professional
 * Android Wearables». Ключа не требует, данные под CC0, и это единственный источник, который
 * работает без учётных записей вообще.
 *
 * <p>Слабое место известно: русских изданий, кроме классики, там практически нет, зато охотно
 * предлагается что-нибудь похожее по звучанию. Отсюда обязательная сверка кандидата.
 */
@Slf4j
@Component
// Спрашивается вторым — не как замена, а как другой источник со своей областью силы.
@Order(2)
public class OpenLibraryClient implements MetadataProvider {

    public static final String PROVIDER = "openlibrary";

    /** Поля, которые нужны: выдача целиком весит на порядок больше и по большей части не нужна. */
    private static final String FIELDS =
            "title,author_name,first_publish_year,publisher,language,isbn,subject,cover_i";

    private final RestClient client;
    private final EnricherProperties.OpenLibrary settings;

    public OpenLibraryClient(
            EnricherProperties properties,
            org.springframework.http.client.ClientHttpRequestFactory requestFactory) {
        this.settings = properties.openLibrary();
        this.client =
                RestClient.builder()
                        .baseUrl(settings.baseUrl())
                        .requestFactory(requestFactory)
                        .build();
    }

    @Override
    public String name() {
        return PROVIDER;
    }

    @Override
    public boolean available() {
        return settings.enabled();
    }

    @Override
    @RateLimiter(name = PROVIDER)
    @CircuitBreaker(name = PROVIDER)
    public Optional<Candidate> find(Lookup lookup) {
        if (lookup.isbn() != null && !lookup.isbn().isBlank()) {
            Optional<Candidate> byIsbn = askByIsbn(lookup.isbn());
            if (byIsbn.isPresent()) {
                return byIsbn;
            }
        }
        for (Query query : textQueries(lookup)) {
            Optional<Candidate> found = askByText(query);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Запросы по убыванию точности.
     *
     * <p>Короткий вариант нужен для длинных названий с подзаголовками: полное название такой книги
     * не находит ничего, первые несколько слов находят её саму.
     */
    private List<Query> textQueries(Lookup lookup) {
        List<Query> queries = new ArrayList<>();
        String title = SearchTerms.cleanTitle(lookup.title());
        String surname = SearchTerms.surname(lookup.authors());
        if (title.isBlank()) {
            return queries;
        }
        if (!surname.isBlank()) {
            queries.add(new Query("название и автор", title + " " + surname));
        }
        queries.add(new Query("название", title));
        String[] words = title.split("\\s+");
        if (words.length > 6) {
            queries.add(
                    new Query("начало названия", String.join(" ", List.of(words).subList(0, 6))));
        }
        return queries;
    }

    private Optional<Candidate> askByIsbn(String isbn) {
        Map<?, ?> response =
                client.get()
                        .uri(
                                uri ->
                                        uri.path("/api/books")
                                                .queryParam("bibkeys", "ISBN:" + isbn)
                                                .queryParam("format", "json")
                                                .queryParam("jscmd", "data")
                                                .build())
                        .retrieve()
                        .body(Map.class);
        if (response == null || response.isEmpty()) {
            return Optional.empty();
        }
        Object first = response.values().iterator().next();
        if (!(first instanceof Map<?, ?> book)) {
            return Optional.empty();
        }
        return Optional.of(fromBookApi(book, isbn));
    }

    private Optional<Candidate> askByText(Query query) {
        SearchResponse response =
                client.get()
                        .uri(
                                uri ->
                                        uri.path("/search.json")
                                                .queryParam("q", query.text())
                                                .queryParam("limit", 1)
                                                .queryParam("fields", FIELDS)
                                                .build())
                        .retrieve()
                        .body(SearchResponse.class);
        if (response == null || response.docs() == null || response.docs().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(fromSearch(response.docs().get(0), query.kind()));
    }

    private Candidate fromSearch(Doc doc, String matchedBy) {
        return new Candidate(
                PROVIDER,
                matchedBy,
                doc.title(),
                doc.authorName() == null ? List.of() : doc.authorName(),
                doc.firstPublishYear(),
                first(doc.language()),
                first(doc.isbn()),
                first(doc.publisher()),
                doc.subject() == null ? List.of() : doc.subject().stream().limit(8).toList(),
                doc.coverI() == null ? null : coverUrl("id", String.valueOf(doc.coverI())));
    }

    /**
     * Разбор ответа службы «книга по ISBN».
     *
     * <p>Она отвечает свободной структурой, а не набором полей, поэтому разбирается вручную. Год
     * там приходит человеческой строкой вида «Sep 29, 2017» — от неё нужны последние четыре цифры.
     */
    private Candidate fromBookApi(Map<?, ?> book, String isbn) {
        List<String> authors = names(book.get("authors"));
        List<String> subjects = names(book.get("subjects"));
        String publisher = names(book.get("publishers")).stream().findFirst().orElse(null);
        return new Candidate(
                PROVIDER,
                "isbn",
                text(book.get("title")),
                authors,
                year(text(book.get("publish_date"))),
                null,
                isbn,
                publisher,
                subjects.stream().limit(8).toList(),
                coverUrl("isbn", isbn));
    }

    private List<String> names(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && map.get("name") != null) {
                names.add(String.valueOf(map.get("name")));
            }
        }
        return names;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer year(String publishDate) {
        if (publishDate == null) {
            return null;
        }
        var matcher =
                java.util.regex.Pattern.compile("(1[5-9]\\d{2}|20\\d{2})").matcher(publishDate);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    /**
     * Обложки лежат на отдельной службе и адресуются либо своим номером, либо ISBN.
     *
     * <p>{@code default=false} обязателен. Без него служба на книгу без обложки отдаёт картинку
     * «обложки нет» — и это выглядит как успех: на живом прогоне одна такая заглушка досталась
     * сразу пяти книгам. С параметром вместо неё приходит 404, то есть честный ответ.
     */
    private String coverUrl(String kind, String key) {
        return "%s/b/%s/%s-L.jpg?default=false".formatted(settings.coversUrl(), kind, key);
    }

    private record Query(String kind, String text) {}

    record SearchResponse(List<Doc> docs) {}

    /** Имена полей у службы записаны через подчёркивание, поэтому проставлены явно. */
    record Doc(
            String title,
            @JsonProperty("author_name") List<String> authorName,
            @JsonProperty("first_publish_year") Integer firstPublishYear,
            List<String> publisher,
            List<String> language,
            List<String> isbn,
            List<String> subject,
            @JsonProperty("cover_i") Integer coverI) {}
}
