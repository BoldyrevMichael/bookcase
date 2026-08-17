package com.bookcase.catalog.repository;

import com.bookcase.catalog.dto.Facets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Темы.
 *
 * <p>Словарь плоский и растёт по мере надобности: тема заводится при первом применении и дальше
 * выбирается из уже существующих. Заводить её заново при каждом наборе значило бы получить в
 * библиотеке java, Java и джаву одновременно.
 */
@Repository
public class ThemeRepository {

    private final JdbcClient jdbc;

    public ThemeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID findOrCreate(String ownerId, String name) {
        jdbc.sql(
                        """
                        INSERT INTO theme (id, owner_id, name) VALUES (:id, :ownerId, :name)
                        ON CONFLICT (owner_id, name) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("ownerId", ownerId)
                .param("name", name)
                .update();
        return jdbc.sql("SELECT id FROM theme WHERE owner_id = :ownerId AND name = :name")
                .param("ownerId", ownerId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    public void link(UUID bookId, List<UUID> themeIds) {
        for (UUID themeId : themeIds) {
            jdbc.sql(
                            """
                            INSERT INTO book_theme (book_id, theme_id) VALUES (:bookId, :themeId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("bookId", bookId)
                    .param("themeId", themeId)
                    .update();
        }
    }

    public void unlinkAll(UUID bookId) {
        jdbc.sql("DELETE FROM book_theme WHERE book_id = :bookId").param("bookId", bookId).update();
    }

    public Map<UUID, List<String>> findByBooks(List<UUID> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        record Row(UUID bookId, String name) {}
        return jdbc
                .sql(
                        """
                        SELECT bt.book_id, t.name
                        FROM book_theme bt JOIN theme t ON t.id = bt.theme_id
                        WHERE bt.book_id IN (:bookIds)
                        ORDER BY t.name
                        """)
                .param("bookIds", bookIds)
                .query(
                        (rs, row) ->
                                new Row(rs.getObject("book_id", UUID.class), rs.getString("name")))
                .list()
                .stream()
                .collect(
                        Collectors.groupingBy(
                                Row::bookId, Collectors.mapping(Row::name, Collectors.toList())));
    }

    /** Все темы владельца с числом книг: то же, что видно перечнем рядом с поиском. */
    public List<Facets.FacetValue> findAll(String ownerId) {
        return jdbc.sql(
                        """
                        SELECT t.name, count(bt.book_id) AS amount
                        FROM theme t LEFT JOIN book_theme bt ON bt.theme_id = t.id
                        WHERE t.owner_id = :ownerId
                        GROUP BY t.name
                        ORDER BY amount DESC, t.name
                        """)
                .param("ownerId", ownerId)
                .query(
                        (rs, row) ->
                                new Facets.FacetValue(rs.getString("name"), rs.getLong("amount")))
                .list();
    }

    /** Темы, на которые больше не ссылается ни одна книга, в словаре не нужны. */
    public void deleteUnused(String ownerId) {
        jdbc.sql(
                        """
                        DELETE FROM theme t
                        WHERE t.owner_id = :ownerId
                          AND NOT EXISTS (SELECT 1 FROM book_theme bt WHERE bt.theme_id = t.id)
                        """)
                .param("ownerId", ownerId)
                .update();
    }
}
