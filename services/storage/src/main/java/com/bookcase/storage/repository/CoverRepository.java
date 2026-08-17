package com.bookcase.storage.repository;

import com.bookcase.storage.dto.StoredCover;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Учёт обложек. */
@Repository
public class CoverRepository {

    private final JdbcClient jdbc;

    public CoverRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredCover> find(String sha256) {
        return jdbc.sql(
                        """
                        SELECT sha256, content_type, size_bytes
                        FROM cover WHERE sha256 = :sha256
                        """)
                .param("sha256", sha256)
                .query(
                        (rs, rowNum) ->
                                new StoredCover(
                                        rs.getString("sha256"),
                                        rs.getString("content_type"),
                                        rs.getLong("size_bytes")))
                .optional();
    }

    /** Записывает, что карточка показывает эту обложку. Повторная запись просто обновляет её. */
    public void link(String sha256, java.util.UUID holderId, String ownerId) {
        jdbc.sql(
                        """
                        INSERT INTO cover_reference (holder_id, sha256, owner_id)
                        VALUES (:holderId, :sha256, :ownerId)
                        ON CONFLICT (holder_id) DO UPDATE SET sha256 = excluded.sha256
                        """)
                .param("holderId", holderId)
                .param("sha256", sha256)
                .param("ownerId", ownerId)
                .update();
    }

    /**
     * Убирает держателя.
     *
     * <p>Отпустить обложку может только тот, кто её держит: чужая карточка на это права не даёт, и
     * несуществующая ничем от чужой не отличается.
     *
     * @return хэш освободившейся обложки, если такой держатель был
     */
    public Optional<String> unlink(java.util.UUID holderId, String ownerId) {
        return jdbc.sql(
                        """
                        DELETE FROM cover_reference
                        WHERE holder_id = :holderId AND owner_id = :ownerId
                        RETURNING sha256
                        """)
                .param("holderId", holderId)
                .param("ownerId", ownerId)
                .query(String.class)
                .optional();
    }

    /**
     * Убирает держателя без оглядки на владельца.
     *
     * <p>Для того, кто обложки кладёт: он же убирает их за книгами, которых не стало. Владельца он
     * при этом не изображает — просто распоряжается тем, что сам и принёс.
     *
     * @return хэш освободившейся обложки, если такой держатель был
     */
    public Optional<String> unlink(java.util.UUID holderId) {
        return jdbc.sql("DELETE FROM cover_reference WHERE holder_id = :holderId RETURNING sha256")
                .param("holderId", holderId)
                .query(String.class)
                .optional();
    }

    /** Сколько карточек показывает эту обложку. */
    public int countHolders(String sha256) {
        return jdbc.sql("SELECT count(*) FROM cover_reference WHERE sha256 = :sha256")
                .param("sha256", sha256)
                .query(Integer.class)
                .optional()
                // Пустого значения тут не бывает — count всегда возвращает строку, — но тип
                // у неё Integer, и молчаливая распаковка пустоты дала бы отказ на ровном месте.
                .map(Integer::intValue)
                .orElse(0);
    }

    /** Убирает саму обложку. Вызывается, когда её больше некому показывать. */
    public void delete(String sha256) {
        jdbc.sql("DELETE FROM cover WHERE sha256 = :sha256").param("sha256", sha256).update();
    }

    /** Обложка одна на всех, у кого есть эта книга: повторная запись ничего не меняет. */
    public void save(StoredCover cover) {
        jdbc.sql(
                        """
                        INSERT INTO cover (sha256, content_type, size_bytes)
                        VALUES (:sha256, :contentType, :size)
                        ON CONFLICT (sha256) DO NOTHING
                        """)
                .param("sha256", cover.sha256())
                .param("contentType", cover.contentType())
                .param("size", cover.sizeBytes())
                .update();
    }
}
