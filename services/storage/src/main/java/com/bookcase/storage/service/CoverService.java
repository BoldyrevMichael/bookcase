package com.bookcase.storage.service;

import com.bookcase.storage.client.ObjectStore;
import com.bookcase.storage.config.StorageProperties;
import com.bookcase.storage.dto.StoredCover;
import com.bookcase.storage.exception.StoredFileNotFoundException;
import com.bookcase.storage.repository.CoverRepository;
import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Приём и выдача обложек.
 *
 * <p>Обложки лежат в своей корзине и отдельно от книг. Разница не в устройстве, а в существе: книга
 * принадлежит владельцу и отдаётся только ему, обложка же приходит из внешнего справочника, у всех
 * одинакова и уже опубликована. Поэтому у неё нет ни владельца, ни списка ссылок, ни пропуска на
 * скачивание.
 *
 * <p>Кладёт обложки только уточнитель — единственный, кто ходит в интернет. Право на это выдано ему
 * ролью служебной учётной записи, а не тем, что он «свой сервис».
 */
@Slf4j
@Service
public class CoverService {

    /** Что вообще имеет смысл принимать: обложка — картинка, и ничем иным быть не может. */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final CoverRepository covers;
    private final ObjectStore objectStore;
    private final StorageProperties properties;

    public CoverService(
            CoverRepository covers, ObjectStore objectStore, StorageProperties properties) {
        this.covers = covers;
        this.objectStore = objectStore;
        this.properties = properties;
    }

    /**
     * Принимает обложку.
     *
     * <p>Имя объекта — хэш содержимого, поэтому одна и та же картинка, пришедшая для десяти книг,
     * хранится однажды. Возвращается тот же хэш: по нему обложка потом и запрашивается.
     */
    @Transactional
    public StoredCover store(byte[] content, String contentType, UUID holderId, String ownerId) {
        String type = normalizeType(contentType);
        String sha256 = hash(content);
        StoredCover cover = new StoredCover(sha256, type, content.length);
        if (covers.find(sha256).isEmpty()) {
            objectStore.put(
                    properties.coversBucket(),
                    sha256,
                    new ByteArrayInputStream(content),
                    content.length,
                    type);
            covers.save(cover);
            log.info("обложка {} принята, {} байт", sha256, content.length);
        }
        // Держатель записывается всегда, даже если такая картинка уже лежала: одна обложка
        // может достаться нескольким карточкам, и убрать её можно только когда уйдут все.
        covers.link(sha256, holderId, ownerId);
        return cover;
    }

    /**
     * Карточка больше не показывает обложку.
     *
     * <p>Сама картинка уходит, когда её перестают показывать все. Без этого обложка переживала бы
     * книгу и оставалась в хранилище навсегда — с файлами книг такого не происходит ровно потому,
     * что у них тот же учёт держателей.
     */
    @Transactional
    public void release(UUID holderId, String ownerId) {
        covers.unlink(holderId, ownerId).ifPresent(this::forgetIfUnused);
    }

    /**
     * То же, но от имени того, кто обложки кладёт.
     *
     * <p>Нужно для случая, когда книгу удалили, пока справочник думал: обложка приезжает к
     * несуществующей карточке, и владелец её уже не отпустит — карточки нет, а значит нет и того,
     * кто пришёл бы с его токеном.
     */
    @Transactional
    public void releaseAsWriter(UUID holderId) {
        covers.unlink(holderId).ifPresent(this::forgetIfUnused);
    }

    private void forgetIfUnused(String sha256) {
        if (covers.countHolders(sha256) == 0) {
            covers.delete(sha256);
            objectStore.delete(properties.coversBucket(), sha256);
            log.info("обложка {} удалена: показывать её больше некому", sha256);
        }
    }

    public StoredCover describe(String sha256) {
        return covers.find(sha256).orElseThrow(() -> new StoredFileNotFoundException(sha256));
    }

    /** Отдаёт содержимое потоком. */
    public void writeTo(String sha256, OutputStream target) throws java.io.IOException {
        describe(sha256);
        objectStore.copyTo(properties.coversBucket(), sha256, target);
    }

    private String normalizeType(String contentType) {
        if (contentType == null) {
            return "image/jpeg";
        }
        String type = contentType.split(";")[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("обложкой может быть только картинка, а не " + type);
        }
        return type;
    }

    private String hash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("в этой JDK нет SHA-256", e);
        }
    }
}
