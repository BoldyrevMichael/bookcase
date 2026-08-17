package com.bookcase.enricher.service;

import com.bookcase.enricher.client.Candidate;
import com.bookcase.enricher.client.Lookup;
import com.bookcase.enricher.client.MetadataProvider;
import com.bookcase.enricher.config.EnricherProperties;
import com.bookcase.enricher.repository.ProviderResponseRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Спрашивает справочник, помня прошлые ответы.
 *
 * <p>Ключ запоминания — не строка запроса, а то, о какой книге спрашивали: справочники опрашиваются
 * несколькими способами подряд, и запоминать каждый способ отдельно значило бы не запомнить ничего.
 */
@Slf4j
@Component
public class ProviderCache {

    private final ProviderResponseRepository responses;
    private final ObjectMapper json;
    private final EnricherProperties properties;
    private final EnrichmentMetrics metrics;

    public ProviderCache(
            ProviderResponseRepository responses,
            ObjectMapper json,
            EnricherProperties properties,
            EnrichmentMetrics metrics) {
        this.responses = responses;
        this.json = json;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Спрашивает справочник или отвечает по памяти.
     *
     * @return найденное; пусто и когда справочник не знает книгу, и когда это уже выяснено раньше
     */
    public Optional<Candidate> ask(MetadataProvider provider, Lookup lookup) {
        String request = requestText(lookup);
        String hash = hash(request);
        Optional<ProviderResponseRepository.Remembered> remembered =
                responses.find(provider.name(), hash, properties.cacheTtl());
        if (remembered.isPresent()) {
            metrics.cacheHit(provider.name());
            return fromMemory(provider, lookup, remembered.get());
        }

        metrics.cacheMiss(provider.name());
        Optional<Candidate> found = provider.find(lookup);
        metrics.lookup(provider.name(), found.isPresent() ? "found" : "not-found");
        responses.remember(
                provider.name(),
                hash,
                request,
                found.isPresent(),
                found.map(this::serialize).orElse(null));
        return found;
    }

    private Optional<Candidate> fromMemory(
            MetadataProvider provider,
            Lookup lookup,
            ProviderResponseRepository.Remembered remembered) {
        if (!remembered.found() || remembered.payload() == null) {
            log.debug("{} уже отвечал, что такой книги не знает", provider.name());
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(remembered.payload(), Candidate.class));
        } catch (JacksonException e) {
            // Запомненное разобрать не вышло — это не повод бросать работу: спросим заново.
            log.warn("запомненный ответ {} не читается, спрашиваю снова", provider.name(), e);
            return provider.find(lookup);
        }
    }

    private String serialize(Candidate candidate) {
        return json.writeValueAsString(candidate);
    }

    /** Из чего складывается вопрос: ISBN, название и авторы, приведённые к общему виду. */
    private String requestText(Lookup lookup) {
        String isbn = lookup.isbn() == null ? "" : lookup.isbn();
        String title = SearchTerms.cleanTitle(lookup.title()).toLowerCase(Locale.ROOT);
        String authors = String.join(" ", SearchTerms.surnames(lookup.authors()));
        return isbn + "|" + title + "|" + authors;
    }

    /**
     * Забывает всё, что запомнено про эту книгу.
     *
     * <p>Вызывается, когда владелец просит уточнить заново: без этого справочник не спросят —
     * прошлый ответ, включая отрицательный, лежит в памяти месяц, и кнопка не делала бы ничего.
     */
    public void forget(Lookup lookup) {
        responses.forgetRequest(hash(requestText(lookup)));
    }

    private String hash(String request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JDK нет SHA-256", e);
        }
    }
}
