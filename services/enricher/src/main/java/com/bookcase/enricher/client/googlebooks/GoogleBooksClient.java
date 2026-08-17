package com.bookcase.enricher.client.googlebooks;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.client.MetadataProvider;
import com.bookcase.enricher.config.EnricherProperties;
import com.bookcase.enricher.service.SearchTerms;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Справочник Google Books.
 *
 * <p>Основной источник: на корпусе он единственный, кто знает русские издания — «100 ошибок Java и
 * как их избежать» Тагира Валеева, «Java Persistence API и Hibernate» ДМК Пресс. Плата за это —
 * ключ и суточная квота в тысячу запросов; ограничитель скорости настроен ровно на неё.
 *
 * <p>Спрашивается по очереди тремя способами: по ISBN, по названию с фамилией автора, по одному
 * названию. Порядок — по убыванию точности: ISBN опознаёт издание однозначно, название с автором
 * почти однозначно, одно название нередко приводит чужую книгу.
 */
@Slf4j
@Component
// Спрашивается первым: на корпусе он единственный, кто знает русские издания.
@Order(1)
public class GoogleBooksClient implements MetadataProvider {

    public static final String PROVIDER = "googlebooks";

    private final RestClient client;
    private final EnricherProperties.Google settings;

    public GoogleBooksClient(
            EnricherProperties properties,
            org.springframework.http.client.ClientHttpRequestFactory requestFactory) {
        this.settings = properties.google();
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

    /**
     * Ищет книгу.
     *
     * <p>Предохранитель и ограничитель скорости стоят здесь, а не вокруг всей задачи: считать нужно
     * именно обращения к чужой службе. Когда предохранитель разомкнут, вызов не уходит, и уточнение
     * просто откладывается — библиотека при этом работает как ни в чём не бывало.
     */
    @Override
    @RateLimiter(name = PROVIDER)
    @CircuitBreaker(name = PROVIDER)
    public Optional<Candidate> find(Lookup lookup) {
        for (Query query : queries(lookup)) {
            Optional<Candidate> found = ask(query);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private List<Query> queries(Lookup lookup) {
        List<Query> queries = new ArrayList<>();
        if (lookup.isbn() != null && !lookup.isbn().isBlank()) {
            queries.add(new Query("isbn", "isbn:" + lookup.isbn()));
        }
        String title = SearchTerms.cleanTitle(lookup.title());
        String surname = SearchTerms.surname(lookup.authors());
        if (!title.isBlank() && !surname.isBlank()) {
            queries.add(
                    new Query(
                            "название и автор",
                            "intitle:\"%s\" inauthor:\"%s\"".formatted(title, surname)));
        }
        if (!title.isBlank()) {
            queries.add(new Query("название", "intitle:\"%s\"".formatted(title)));
        }
        return queries;
    }

    private Optional<Candidate> ask(Query query) {
        GoogleResponse response =
                client.get()
                        .uri(uri -> build(uri, query.expression()))
                        .retrieve()
                        .body(GoogleResponse.class);
        if (response == null || response.items() == null || response.items().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toCandidate(response.items().get(0).volumeInfo(), query.kind()));
    }

    private java.net.URI build(UriBuilder uri, String expression) {
        return uri.path("/volumes")
                .queryParam("q", expression)
                .queryParam("maxResults", 1)
                // Без указания страны служба иногда отвечает отказом «не поддерживается
                // в вашей стране» — она смотрит на адрес вызывающего.
                .queryParam("country", "US")
                .queryParam("key", settings.apiKey())
                .build();
    }

    private Candidate toCandidate(VolumeInfo info, String matchedBy) {
        return new Candidate(
                PROVIDER,
                matchedBy,
                info.title(),
                info.authors() == null ? List.of() : info.authors(),
                year(info.publishedDate()),
                info.language(),
                isbn13(info.industryIdentifiers()),
                info.publisher(),
                info.categories() == null ? List.of() : info.categories(),
                cover(info.imageLinks()));
    }

    private Integer year(String publishedDate) {
        if (publishedDate == null || publishedDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(publishedDate.substring(0, 4));
        } catch (NumberFormatException _) {
            return null;
        }
    }

    private String isbn13(List<IndustryIdentifier> identifiers) {
        if (identifiers == null) {
            return null;
        }
        return identifiers.stream()
                .filter(id -> "ISBN_13".equals(id.type()))
                .map(IndustryIdentifier::identifier)
                .findFirst()
                .orElse(null);
    }

    /**
     * Ссылка на обложку.
     *
     * <p>Правится ровно две вещи: протокол на https и загнутый уголок, который служба пририсовывает
     * к картинке параметром {@code edge=curl}.
     *
     * <p>Соблазн попросить увеличенный размер параметром {@code zoom} обманчив — проверено живьём:
     * по исходному адресу приходит настоящая обложка в JPEG, а с {@code zoom=2} служба отдаёт PNG с
     * надписью «изображение недоступно», причём одинаковый для разных книг. В библиотеке это
     * выглядело так, будто у четырёх книг одна обложка на всех.
     */
    private String cover(ImageLinks links) {
        if (links == null) {
            return null;
        }
        String url = links.thumbnail() != null ? links.thumbnail() : links.smallThumbnail();
        if (url == null) {
            return null;
        }
        return url.replace("http://", "https://").replace("&edge=curl", "");
    }

    private record Query(String kind, String expression) {}

    record GoogleResponse(List<Item> items) {}

    record Item(VolumeInfo volumeInfo) {}

    record VolumeInfo(
            String title,
            List<String> authors,
            String publisher,
            String publishedDate,
            String language,
            List<String> categories,
            List<IndustryIdentifier> industryIdentifiers,
            ImageLinks imageLinks) {}

    record IndustryIdentifier(String type, String identifier) {}

    record ImageLinks(String smallThumbnail, String thumbnail) {}
}
