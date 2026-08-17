package com.bookcase.catalog.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Авторы.
 *
 * <p>Автор — отдельная запись, а не строка в карточке: иначе на вопрос «что у меня есть Мартина»
 * пришлось бы отвечать поиском по подстроке, а список авторов библиотеки было бы неоткуда взять.
 */
@Repository
public class AuthorRepository {

    private final JdbcClient jdbc;

    public AuthorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Возвращает автора, заводя запись при первой встрече. */
    public UUID findOrCreate(String ownerId, String name) {
        jdbc.sql(
                        """
                        INSERT INTO author (id, owner_id, name) VALUES (:id, :ownerId, :name)
                        ON CONFLICT (owner_id, name) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("ownerId", ownerId)
                .param("name", name)
                .update();
        return jdbc.sql("SELECT id FROM author WHERE owner_id = :ownerId AND name = :name")
                .param("ownerId", ownerId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    public void link(UUID bookId, List<UUID> authorIds) {
        for (int position = 0; position < authorIds.size(); position++) {
            jdbc.sql(
                            """
                            INSERT INTO book_author (book_id, author_id, position)
                            VALUES (:bookId, :authorId, :position)
                            ON CONFLICT (book_id, author_id) DO NOTHING
                            """)
                    .param("bookId", bookId)
                    .param("authorId", authorIds.get(position))
                    .param("position", position)
                    .update();
        }
    }

    public void unlinkAll(UUID bookId) {
        jdbc.sql("DELETE FROM book_author WHERE book_id = :bookId")
                .param("bookId", bookId)
                .update();
    }

    /** Авторы сразу нескольких книг: выбираются одним запросом, а не по книге на запрос. */
    public Map<UUID, List<String>> findByBooks(List<UUID> bookIds) {
        if (bookIds.isEmpty()) {
            return Map.of();
        }
        record Row(UUID bookId, String name) {}
        return jdbc
                .sql(
                        """
                        SELECT ba.book_id, a.name
                        FROM book_author ba JOIN author a ON a.id = ba.author_id
                        WHERE ba.book_id IN (:bookIds)
                        ORDER BY ba.book_id, ba.position
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
}
