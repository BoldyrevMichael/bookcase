package com.bookcase.enricher.repository;

import com.bookcase.enricher.dto.EnrichmentTask;
import com.bookcase.enricher.service.state.EnrichmentStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Очередь уточнения: что спросить у справочника и когда. */
@Repository
public class EnrichmentTaskRepository {

    private final JdbcClient jdbc;

    public EnrichmentTaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ставит книгу в очередь.
     *
     * <p>Повторное событие о той же книге задачу не удваивает: ключ таблицы — сама книга.
     */
    public void enqueue(
            UUID bookId, String ownerId, String title, String authors, String isbn, Integer year) {
        jdbc.sql(
                        """
                        INSERT INTO enrichment_task
                            (book_id, owner_id, title, authors, isbn, year, status)
                        VALUES (:bookId, :ownerId, :title, :authors, :isbn, :year, 'WAITING')
                        ON CONFLICT (book_id) DO NOTHING
                        """)
                .param("bookId", bookId)
                .param("ownerId", ownerId)
                .param("title", title)
                .param("authors", authors)
                .param("isbn", isbn)
                .param("year", year)
                .update();
    }

    /**
     * Возвращает книгу в очередь по просьбе владельца.
     *
     * <p>В отличие от появления книги, здесь задача заводится заново независимо от того, чем
     * закончилась прошлая: закрытая задача не значит исчерпанная — справочник мог с тех пор
     * пополниться, а владелец мог поправить название, по которому его спрашивают.
     *
     * <p>Стоящую в очереди задачу просьба не трогает: она и так дождётся своего часа, а сброс
     * счётчика попыток означал бы, что частым нажатием можно ходить к справочнику без конца.
     *
     * @return true, если задача действительно поставлена в очередь
     */
    public boolean requeue(
            UUID bookId, String ownerId, String title, String authors, String isbn, Integer year) {
        return jdbc.sql(
                                """
                        INSERT INTO enrichment_task
                            (book_id, owner_id, title, authors, isbn, year, status)
                        VALUES (:bookId, :ownerId, :title, :authors, :isbn, :year, 'WAITING')
                        ON CONFLICT (book_id) DO UPDATE
                        SET status = 'WAITING', attempts = 0, next_attempt_at = now(),
                            last_failure = NULL, provider = NULL,
                            title = excluded.title, authors = excluded.authors,
                            isbn = excluded.isbn, year = excluded.year, updated_at = now()
                        WHERE enrichment_task.status <> 'WAITING'
                        """)
                        .param("bookId", bookId)
                        .param("ownerId", ownerId)
                        .param("title", title)
                        .param("authors", authors)
                        .param("isbn", isbn)
                        .param("year", year)
                        .update()
                > 0;
    }

    /**
     * Забирает пачку задач, которым пришёл срок.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — чтобы вторая копия сервиса брала следующие задачи, а не
     * ждала первую и не бралась за те же самые. Уточнителей может быть несколько, а квота у
     * справочника одна на всех, и двойная работа тратит её впустую.
     */
    public List<EnrichmentTask> claimDue(int limit) {
        return jdbc.sql(
                        """
                        SELECT book_id, owner_id, title, authors, isbn, year, attempts
                        FROM enrichment_task
                        WHERE status = 'WAITING' AND next_attempt_at <= now()
                        ORDER BY next_attempt_at
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query(
                        (rs, rowNum) ->
                                new EnrichmentTask(
                                        rs.getObject("book_id", UUID.class),
                                        rs.getString("owner_id"),
                                        rs.getString("title"),
                                        rs.getString("authors"),
                                        rs.getString("isbn"),
                                        rs.getObject("year", Integer.class),
                                        rs.getInt("attempts")))
                .list();
    }

    /**
     * Убирает задачу вместе с книгой.
     *
     * <p>Не «помечает отменённой», а удаляет: книги нет, и хранить о ней запись незачем. Учёт
     * обработанных событий при этом остаётся — он про доставку, а не про книги.
     *
     * @return сколько задач убрано; ноль означает, что задачи и не было
     */
    public int forget(UUID bookId) {
        return jdbc.sql("DELETE FROM enrichment_task WHERE book_id = :bookId")
                .param("bookId", bookId)
                .update();
    }

    /** Уточнение состоялось: карточка дополнена, больше эту книгу спрашивать незачем. */
    public void markDone(UUID bookId, String provider) {
        jdbc.sql(
                        """
                        UPDATE enrichment_task
                        SET status = 'DONE', provider = :provider, last_failure = NULL,
                            attempts = attempts + 1, updated_at = now()
                        WHERE book_id = :bookId
                        """)
                .param("bookId", bookId)
                .param("provider", provider)
                .update();
    }

    /**
     * Справочники ответили, но ничего подходящего не нашлось.
     *
     * <p>Это не неудача: переспрашивать бессмысленно до тех пор, пока справочник не пополнится.
     * Задача закрывается, а книга остаётся с тем, что дал файл.
     */
    public void markNotFound(UUID bookId) {
        jdbc.sql(
                        """
                        UPDATE enrichment_task
                        SET status = 'NOT_FOUND', attempts = attempts + 1,
                            last_failure = 'справочники не знают эту книгу', updated_at = now()
                        WHERE book_id = :bookId
                        """)
                .param("bookId", bookId)
                .update();
    }

    /** Спросить не вышло: сеть, отказ службы, разомкнутый предохранитель. Пробуем позже. */
    public void reschedule(UUID bookId, Duration delay, String reason) {
        jdbc.sql(
                        """
                        UPDATE enrichment_task
                        SET attempts = attempts + 1, next_attempt_at = :next,
                            last_failure = :reason, updated_at = now()
                        WHERE book_id = :bookId
                        """)
                .param("bookId", bookId)
                .param("next", OffsetDateTime.now(ZoneOffset.UTC).plus(delay))
                .param("reason", reason)
                .update();
    }

    /** Попытки исчерпаны. Книга остаётся как есть, задача больше не берётся. */
    public void giveUp(UUID bookId, String reason) {
        jdbc.sql(
                        """
                        UPDATE enrichment_task
                        SET status = 'GAVE_UP', attempts = attempts + 1, last_failure = :reason,
                            updated_at = now()
                        WHERE book_id = :bookId
                        """)
                .param("bookId", bookId)
                .param("reason", reason)
                .update();
    }

    /**
     * Сколько задач в каком состоянии.
     *
     * <p>Отсюда берётся показатель «сколько книг ждёт уточнения»: он же отвечает на вопрос, не
     * встало ли всё из-за исчерпанной квоты.
     */
    public Map<EnrichmentStatus, Integer> countByStatus() {
        return jdbc
                .sql("SELECT status, count(*) AS amount FROM enrichment_task GROUP BY status")
                .query(
                        (rs, rowNum) ->
                                Map.entry(
                                        EnrichmentStatus.valueOf(rs.getString("status")),
                                        rs.getInt("amount")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
