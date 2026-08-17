package com.bookcase.enricher.repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Запомненные ответы справочников.
 *
 * <p>Кэш нужен не ради скорости, а ради квоты: бесплатный ключ Google Books даёт тысячу запросов в
 * сутки, и повторный вопрос о той же книге — это отнятая возможность спросить о другой.
 *
 * <p>Отрицательные ответы запоминаются наравне с положительными. «Такой книги здесь нет» —
 * полноценный ответ, и переспрашивать его на каждой попытке значит потратить всю квоту на книги,
 * которых в справочнике не было и через час не появится.
 */
@Repository
public class ProviderResponseRepository {

    private final JdbcClient jdbc;

    public ProviderResponseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Что справочник ответил в прошлый раз: найдено ли и что именно. */
    public record Remembered(boolean found, String payload) {}

    public Optional<Remembered> find(String provider, String requestHash, Duration ttl) {
        return jdbc.sql(
                        """
                        -- cast, а не payload::text: двоеточие в SQL здесь занято под имена
                        -- параметров, и «::text» разбирается как параметр с именем text.
                        SELECT found, cast(payload as text) AS payload
                        FROM provider_response
                        WHERE provider = :provider AND request_hash = :hash
                          AND created_at > :since
                        """)
                .param("provider", provider)
                .param("hash", requestHash)
                .param("since", OffsetDateTime.now(ZoneOffset.UTC).minus(ttl))
                .query(
                        (rs, rowNum) ->
                                new Remembered(rs.getBoolean("found"), rs.getString("payload")))
                .optional();
    }

    public void remember(
            String provider,
            String requestHash,
            String requestText,
            boolean found,
            String payload) {
        jdbc.sql(
                        """
                        INSERT INTO provider_response
                            (provider, request_hash, request_text, found, payload)
                        VALUES (:provider, :hash, :text, :found, cast(:payload as jsonb))
                        ON CONFLICT (provider, request_hash) DO UPDATE
                        SET found = excluded.found, payload = excluded.payload,
                            created_at = now()
                        """)
                .param("provider", provider)
                .param("hash", requestHash)
                .param("text", requestText)
                .param("found", found)
                .param("payload", payload)
                .update();
    }

    /**
     * Забывает ответы на один конкретный вопрос.
     *
     * <p>Нужно для повторного уточнения по просьбе владельца: иначе справочник не спросят вовсе —
     * ответ ведь уже запомнен, в том числе отрицательный, и запомнен надолго.
     */
    public void forgetRequest(String requestHash) {
        jdbc.sql("DELETE FROM provider_response WHERE request_hash = :hash")
                .param("hash", requestHash)
                .update();
    }

    /** Убирает ответы, которые залежались: справочники пополняются. */
    public int forget(Duration ttl) {
        return jdbc.sql("DELETE FROM provider_response WHERE created_at < :before")
                .param("before", OffsetDateTime.now(ZoneOffset.UTC).minus(ttl))
                .update();
    }
}
