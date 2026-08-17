package com.bookcase.storage.service;

import com.bookcase.storage.client.ObjectStore;
import com.bookcase.storage.config.StorageProperties;
import com.bookcase.storage.dto.StoredFile;
import com.bookcase.storage.dto.UploadResult;
import com.bookcase.storage.exception.StoredFileNotFoundException;
import com.bookcase.storage.repository.StoredFileRepository;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Приём, выдача и удаление файлов. */
@Slf4j
@Service
public class FileStorageService {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StoredFileRepository repository;
    private final ObjectStore objectStore;
    private final StorageProperties properties;
    private final StorageMetrics metrics;

    public FileStorageService(
            StoredFileRepository repository,
            ObjectStore objectStore,
            StorageProperties properties,
            StorageMetrics metrics) {
        this.repository = repository;
        this.objectStore = objectStore;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Принимает файл.
     *
     * <p>Содержимое читается дважды и оба раза потоком: сначала считаются хэш, контрольная сумма и
     * размер, потом — если таких байтов ещё не было — файл переливается в хранилище. Второе чтение
     * идёт с диска, куда приёмник запроса уже сложил тело, и стоит несравнимо меньше, чем загрузка
     * в хранилище копии того, что там есть. Именно поэтому хэш считается до записи, а не заодно с
     * ней.
     */
    // rollbackFor: по умолчанию Spring откатывает только непроверяемые отказы, а здесь
    // наружу выходит IOException — без этого запись о файле осталась бы в базе,
    // хотя самих байтов в хранилище нет.
    @Transactional(rollbackFor = IOException.class)
    public UploadResult store(String ownerId, MultipartFile file) throws IOException {
        ContentDigest digest;
        try (InputStream content = file.getInputStream()) {
            digest = ContentDigest.of(content);
        }

        String contentType =
                file.getContentType() == null ? DEFAULT_CONTENT_TYPE : file.getContentType();
        boolean alreadyStored = repository.fileExists(digest.sha256());
        metrics.accepted(digest.sizeBytes(), alreadyStored);
        if (!alreadyStored) {
            try (InputStream content = file.getInputStream()) {
                objectStore.put(
                        properties.booksBucket(),
                        digest.sha256(),
                        content,
                        digest.sizeBytes(),
                        contentType);
            }
            repository.insertFile(digest.sha256(), digest.sizeBytes(), digest.crc32(), contentType);
            log.info("принят новый файл {} размером {} байт", digest.sha256(), digest.sizeBytes());
        } else {
            log.info("файл {} уже есть в хранилище, второй объект не создаётся", digest.sha256());
        }

        repository.addReference(digest.sha256(), ownerId, originalName(file));
        return new UploadResult(
                digest.sha256(), digest.sizeBytes(), digest.crc32(), contentType, alreadyStored);
    }

    /** Сведения о файле этого владельца. Чужой файл для него не существует. */
    public StoredFile find(String sha256, String ownerId) {
        return repository
                .findForOwner(sha256, ownerId)
                .orElseThrow(() -> new StoredFileNotFoundException(sha256));
    }

    public List<StoredFile> findAll(List<String> hashes, String ownerId) {
        List<StoredFile> found = repository.findAllForOwner(hashes, ownerId);
        if (found.size() != hashes.size()) {
            Set<String> available =
                    found.stream().map(StoredFile::sha256).collect(Collectors.toSet());
            String missing =
                    hashes.stream()
                            .filter(hash -> !available.contains(hash))
                            .findFirst()
                            .orElseThrow();
            throw new StoredFileNotFoundException(missing);
        }
        return found;
    }

    /** Открывает содержимое на чтение. Поток закрывает вызывающий. */
    public InputStream open(StoredFile file) {
        return objectStore.open(properties.booksBucket(), file.sha256());
    }

    /**
     * Убирает ссылку владельца на файл. Объект удаляется, только когда ушёл последний владелец: те
     * же байты могли положить себе и другие.
     */
    @Transactional
    public void release(String sha256, String ownerId) {
        if (!repository.removeReference(sha256, ownerId)) {
            throw new StoredFileNotFoundException(sha256);
        }
        if (repository.countReferences(sha256) == 0) {
            repository.deleteFile(sha256);
            objectStore.delete(properties.booksBucket(), sha256);
            log.info("файл {} удалён: ссылок на него не осталось", sha256);
        }
    }

    private static String originalName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "file" : name;
    }
}
