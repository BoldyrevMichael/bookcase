package com.bookcase.catalog.repository;

import com.bookcase.catalog.dto.CollectionView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Подборки — списки книг, собранных человеком с какой-то целью. */
@Repository
public class CollectionRepository {

    private final JdbcClient jdbc;

    public CollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(String ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        """
                        INSERT INTO collection (id, owner_id, name) VALUES (:id, :ownerId, :name)
                        ON CONFLICT (owner_id, name) DO NOTHING
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .param("name", name)
                .update();
        return jdbc.sql("SELECT id FROM collection WHERE owner_id = :ownerId AND name = :name")
                .param("ownerId", ownerId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    public List<CollectionView> findAll(String ownerId) {
        return jdbc.sql(
                        """
                        SELECT c.id, c.name, c.created_at, count(cb.book_id) AS amount
                        FROM collection c LEFT JOIN collection_book cb ON cb.collection_id = c.id
                        WHERE c.owner_id = :ownerId
                        GROUP BY c.id, c.name, c.created_at
                        ORDER BY c.created_at DESC
                        """)
                .param("ownerId", ownerId)
                .query(
                        (rs, row) ->
                                new CollectionView(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getLong("amount"),
                                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public Optional<UUID> find(UUID id, String ownerId) {
        return jdbc.sql("SELECT id FROM collection WHERE id = :id AND owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .query(UUID.class)
                .optional();
    }

    public boolean delete(UUID id, String ownerId) {
        return jdbc.sql("DELETE FROM collection WHERE id = :id AND owner_id = :ownerId")
                        .param("id", id)
                        .param("ownerId", ownerId)
                        .update()
                > 0;
    }

    public void addBook(UUID collectionId, UUID bookId) {
        jdbc.sql(
                        """
                        INSERT INTO collection_book (collection_id, book_id)
                        VALUES (:collectionId, :bookId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("collectionId", collectionId)
                .param("bookId", bookId)
                .update();
    }

    public void removeBook(UUID collectionId, UUID bookId) {
        jdbc.sql(
                        """
                        DELETE FROM collection_book
                        WHERE collection_id = :collectionId AND book_id = :bookId
                        """)
                .param("collectionId", collectionId)
                .param("bookId", bookId)
                .update();
    }
}
