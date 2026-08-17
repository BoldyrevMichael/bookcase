package com.bookcase.storage.repository;

import com.bookcase.storage.dto.StoredFile;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Учёт хранимых файлов и ссылок на них.
 *
 * <p>Запросов без владельца здесь не бывает: файл, на который у обратившегося нет ссылки, для него
 * не существует.
 */
@Repository
public class StoredFileRepository {

    private final JdbcClient jdbc;

    public StoredFileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Заводит запись о файле, если такого ещё не было.
     *
     * @return {@code true}, если запись появилась только что
     */
    public boolean insertFile(String sha256, long sizeBytes, long crc32, String contentType) {
        return jdbc.sql(
                                """
                        INSERT INTO stored_file (sha256, size_bytes, crc32, content_type)
                        VALUES (:sha256, :size, :crc32, :contentType)
                        ON CONFLICT (sha256) DO NOTHING
                        """)
                        .param("sha256", sha256)
                        .param("size", sizeBytes)
                        .param("crc32", crc32)
                        .param("contentType", contentType)
                        .update()
                > 0;
    }

    /**
     * Привязывает файл к владельцу.
     *
     * @return {@code true}, если ссылка появилась только что
     */
    public boolean addReference(String sha256, String ownerId, String originalName) {
        return jdbc.sql(
                                """
                        INSERT INTO file_reference (owner_id, sha256, original_name)
                        VALUES (:ownerId, :sha256, :originalName)
                        ON CONFLICT (owner_id, sha256) DO NOTHING
                        """)
                        .param("ownerId", ownerId)
                        .param("sha256", sha256)
                        .param("originalName", originalName)
                        .update()
                > 0;
    }

    public boolean removeReference(String sha256, String ownerId) {
        return jdbc.sql("DELETE FROM file_reference WHERE owner_id = :ownerId AND sha256 = :sha256")
                        .param("ownerId", ownerId)
                        .param("sha256", sha256)
                        .update()
                > 0;
    }

    /** Сколько владельцев держат этот файл. Ноль означает, что объект пора удалять. */
    public int countReferences(String sha256) {
        return jdbc.sql("SELECT count(*) FROM file_reference WHERE sha256 = :sha256")
                .param("sha256", sha256)
                .query(Integer.class)
                .optional()
                // Пустого значения тут не бывает — count всегда возвращает строку, — но тип
                // у неё Integer, и молчаливая распаковка пустоты дала бы отказ на ровном месте.
                .map(Integer::intValue)
                .orElse(0);
    }

    public void deleteFile(String sha256) {
        jdbc.sql("DELETE FROM stored_file WHERE sha256 = :sha256").param("sha256", sha256).update();
    }

    public boolean fileExists(String sha256) {
        return jdbc.sql("SELECT count(*) FROM stored_file WHERE sha256 = :sha256")
                        .param("sha256", sha256)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** Файл вместе с именем, под которым его положил именно этот владелец. */
    public Optional<StoredFile> findForOwner(String sha256, String ownerId) {
        return jdbc.sql(
                        """
                        SELECT f.sha256, f.size_bytes, f.crc32, f.content_type, r.original_name
                        FROM file_reference r
                        JOIN stored_file f ON f.sha256 = r.sha256
                        WHERE r.owner_id = :ownerId AND r.sha256 = :sha256
                        """)
                .param("ownerId", ownerId)
                .param("sha256", sha256)
                .query(StoredFileRepository::mapStoredFile)
                .optional();
    }

    /** То же для нескольких файлов сразу: чужие и несуществующие просто не вернутся. */
    public List<StoredFile> findAllForOwner(List<String> hashes, String ownerId) {
        if (hashes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(
                        """
                        SELECT f.sha256, f.size_bytes, f.crc32, f.content_type, r.original_name
                        FROM file_reference r
                        JOIN stored_file f ON f.sha256 = r.sha256
                        WHERE r.owner_id = :ownerId AND r.sha256 IN (:hashes)
                        """)
                .param("ownerId", ownerId)
                .param("hashes", hashes)
                .query(StoredFileRepository::mapStoredFile)
                .list();
    }

    private static StoredFile mapStoredFile(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new StoredFile(
                rs.getString("sha256"),
                rs.getLong("size_bytes"),
                rs.getLong("crc32"),
                rs.getString("content_type"),
                rs.getString("original_name"));
    }
}
