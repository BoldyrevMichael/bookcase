package com.bookcase.storage.controller;

import com.bookcase.storage.dto.DownloadTicket;
import com.bookcase.storage.dto.StoredFile;
import com.bookcase.storage.dto.UploadResult;
import com.bookcase.storage.exception.StoredFileNotFoundException;
import com.bookcase.storage.service.DownloadTickets;
import com.bookcase.storage.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {

    private final FileStorageService files;
    private final DownloadTickets tickets;

    public FileController(FileStorageService files, DownloadTickets tickets) {
        this.files = files;
        this.tickets = tickets;
    }

    /** Приём файла. Владелец берётся из токена, а не из запроса. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResult upload(
            @AuthenticationPrincipal Jwt token, @RequestParam("file") MultipartFile file)
            throws IOException {
        return files.store(token.getSubject(), file);
    }

    /** Выдача файла по токену обратившегося. */
    @GetMapping("/{sha256}")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String sha256, @AuthenticationPrincipal Jwt token) {
        return stream(files.find(sha256, token.getSubject()));
    }

    /**
     * Выдача файла по подписанному пропуску.
     *
     * <p>Пропуск стоит в пути, а не в параметре запроса, и это не украшение: шлюз перед сервисами
     * решает, пускать ли запрос без входа, по пути — параметры он при этом не смотрит. Ссылка с
     * пропуском в параметре до сервиса просто не дошла бы.
     */
    @GetMapping("/{sha256}/download/{ticket}")
    public ResponseEntity<StreamingResponseBody> downloadByTicket(
            @PathVariable String sha256, @PathVariable String ticket) {
        String ownerId =
                tickets.verify(ticket, sha256)
                        .orElseThrow(() -> new StoredFileNotFoundException(sha256));
        return stream(files.find(sha256, ownerId));
    }

    private ResponseEntity<StreamingResponseBody> stream(StoredFile file) {

        StreamingResponseBody body =
                output -> {
                    try (InputStream content = files.open(file)) {
                        content.transferTo(output);
                    }
                };
        return ResponseEntity.ok()
                .headers(downloadHeaders(file))
                .contentLength(file.sizeBytes())
                .body(body);
    }

    /** Выписывает подписанный пропуск на скачивание этого файла. */
    @PostMapping("/{sha256}/ticket")
    public DownloadTicket issueTicket(
            @PathVariable String sha256, @AuthenticationPrincipal Jwt token) {
        String ownerId = token.getSubject();
        files.find(sha256, ownerId);
        return tickets.issue(sha256, ownerId);
    }

    /** Убирает ссылку владельца на файл. */
    @DeleteMapping("/{sha256}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String sha256, @AuthenticationPrincipal Jwt token) {
        files.release(sha256, token.getSubject());
    }

    /**
     * Заголовки выдачи.
     *
     * <p>Тип всегда «поток байтов», а расположение — вложение: содержимое пришло от пользователя, и
     * браузеру незачем пытаться его показать. Имя передаётся в кодировке UTF-8 — названия книг
     * по-русски иначе доедут искажёнными.
     */
    private static HttpHeaders downloadHeaders(StoredFile file) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename(file.originalName(), StandardCharsets.UTF_8)
                        .build());
        headers.add("X-Content-Type-Options", "nosniff");
        return headers;
    }
}
