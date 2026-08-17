package com.bookcase.storage.controller;

import com.bookcase.storage.config.StorageProperties;
import com.bookcase.storage.dto.StoredCover;
import com.bookcase.storage.service.CoverService;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Обложки книг.
 *
 * <p>Кладёт их только уточнитель: право даётся ролью служебной учётной записи. Читать может любой,
 * кто вошёл, — открытым адрес не делается намеренно, иначе по нему можно было бы проверять состав
 * чужой библиотеки: хэш обложки вычисляется из той же картинки у справочника, а ответ на запрос
 * сообщал бы, есть ли такая книга.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/covers")
public class CoverController {

    private static final String COVERS_WRITER = "ROLE_covers-writer";

    private final CoverService covers;
    private final StorageProperties properties;

    public CoverController(CoverService covers, StorageProperties properties) {
        this.covers = covers;
        this.properties = properties;
    }

    /**
     * Принимает обложку целиком в теле запроса.
     *
     * <p>Не multipart: отправитель здесь не браузер с формой, а сервис, у которого на руках готовые
     * байты. Размер ограничен — содержимое пришло из интернета, и сколько его будет, заранее
     * неизвестно.
     */
    @PostMapping(consumes = MediaType.ALL_VALUE)
    @PreAuthorize("hasRole('covers-writer')")
    public ResponseEntity<StoredCover> upload(
            @RequestBody byte[] content,
            @RequestParam UUID holder,
            @RequestParam String owner,
            @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        long limit = properties.maxCoverSize().toBytes();
        if (content.length > limit) {
            throw new IllegalArgumentException(
                    "обложка больше допустимого: %d байт при пределе %d"
                            .formatted(content.length, limit));
        }
        return ResponseEntity.ok(covers.store(content, contentType, holder, owner));
    }

    /**
     * Отдаёт обложку.
     *
     * <p>Кэшируется надолго — содержимое адресуется хэшем и меняться не может, — но только в
     * браузере запросившего: {@code private} потому, что ответ выдан вошедшему и общим кэшам
     * посредников не предназначен.
     */
    @GetMapping("/{sha256}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String sha256) {
        StoredCover cover = covers.describe(sha256);
        StreamingResponseBody body = target -> write(sha256, target);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, cover.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(cover.sizeBytes()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000, immutable")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * Карточка удалена — обложка ей больше не нужна.
     *
     * <p>Отпускает её сам владелец своим токеном: право на это даёт не роль, а то, что карточка
     * его. Картинка исчезнет, только если её не показывает больше никто.
     */
    @DeleteMapping("/holders/{holder}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @PathVariable UUID holder,
            @AuthenticationPrincipal Jwt token,
            Authentication authentication) {
        if (canManageCovers(authentication)) {
            covers.releaseAsWriter(holder);
            return;
        }
        covers.release(holder, token.getSubject());
    }

    /**
     * Тот, кто обложки кладёт, может их и убирать.
     *
     * <p>Обычно обложку отпускает владелец книги своим токеном. Но бывает, что отпускать её некому:
     * книгу удалили, пока справочник думал, и картинка приехала к несуществующей карточке. Тогда её
     * убирает тот же, кто принёс, — уточнитель.
     */
    private boolean canManageCovers(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> COVERS_WRITER.equals(authority.getAuthority()));
    }

    private void write(String sha256, OutputStream target) throws IOException {
        covers.writeTo(sha256, target);
    }
}
