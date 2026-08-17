package com.bookcase.storage.service;

import com.bookcase.storage.client.ObjectStore;
import com.bookcase.storage.config.StorageProperties;
import com.bookcase.storage.dto.ArchiveLocation;
import com.bookcase.storage.dto.StoredFile;
import com.bookcase.storage.exception.StoredFileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Сборка архива в хранилище — второй приёмник того же сборщика.
 *
 * <p>Архив сначала складывается во временный файл и только потом уезжает в корзину: размер готового
 * архива заранее неизвестен, а хранилищу его нужно знать. Держать вместо этого открытым
 * многочастную загрузку на всё время сборки — значит связать её живучесть со скоростью чтения сотен
 * файлов.
 *
 * <p>Собранные архивы живут сутки: правило автоудаления задано на корзине. Это результат, который
 * всегда можно построить заново.
 */
@Slf4j
@Service
public class ExportService {

    private static final String ARCHIVE_CONTENT_TYPE = "application/zip";

    private final ArchiveAssembler assembler;
    private final ObjectStore objectStore;
    private final StorageProperties properties;

    public ExportService(
            ArchiveAssembler assembler, ObjectStore objectStore, StorageProperties properties) {
        this.assembler = assembler;
        this.objectStore = objectStore;
        this.properties = properties;
    }

    /**
     * Проверяет, что архив собран и принадлежит этому владельцу.
     *
     * @return размер архива
     */
    public long requireArchive(String ownerId, String archiveName) {
        return objectStore
                .sizeOf(properties.exportsBucket(), keyFor(ownerId, archiveName))
                .orElseThrow(() -> new StoredFileNotFoundException(archiveName));
    }

    /** Переливает готовый архив в приёмник — так он уходит в ответ на запрос. */
    public void streamArchive(String ownerId, String archiveName, OutputStream target)
            throws IOException {
        objectStore.copyTo(properties.exportsBucket(), keyFor(ownerId, archiveName), target);
    }

    private String keyFor(String ownerId, String archiveName) {
        return ownerId + "/" + archiveName + ".zip";
    }

    public ArchiveLocation export(String ownerId, String archiveName, List<StoredFile> files)
            throws IOException {
        Path archive = Files.createTempFile("bookcase-export-", ".zip");
        try {
            try (OutputStream target = Files.newOutputStream(archive)) {
                assembler.assemble(files, target);
            }
            // Имя объекта начинается с владельца: по нему же потом проверяется, кому
            // архив принадлежит, и чужой пропуск на него не выпишешь.
            String key = keyFor(ownerId, archiveName);
            objectStore.putFile(properties.exportsBucket(), key, archive, ARCHIVE_CONTENT_TYPE);
            long size = Files.size(archive);
            log.info("собран архив {} из {} файлов, {} байт", key, files.size(), size);
            return new ArchiveLocation(key, size, files.size());
        } finally {
            Files.deleteIfExists(archive);
        }
    }
}
