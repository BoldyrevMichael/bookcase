package com.bookcase.storage.controller;

import com.bookcase.storage.dto.ArchiveLocation;
import com.bookcase.storage.dto.ArchiveRequest;
import com.bookcase.storage.dto.DownloadTicket;
import com.bookcase.storage.dto.StoredFile;
import com.bookcase.storage.service.ArchiveAssembler;
import com.bookcase.storage.service.DownloadTickets;
import com.bookcase.storage.service.ExportService;
import com.bookcase.storage.service.FileStorageService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Архивы.
 *
 * <p>Собирает их один и тот же сборщик, разница только в приёмнике: пачку файлов пользователь
 * получает прямо в ответе, а полный экспорт библиотеки складывается в хранилище — его собирают в
 * фоне, и ждать с открытым соединением там некому.
 */
@RestController
@RequestMapping("/api/v1/archives")
public class ArchiveController {

    private final FileStorageService files;
    private final ArchiveAssembler assembler;
    private final ExportService exports;
    private final DownloadTickets tickets;

    public ArchiveController(
            FileStorageService files,
            ArchiveAssembler assembler,
            ExportService exports,
            DownloadTickets tickets) {
        this.files = files;
        this.assembler = assembler;
        this.exports = exports;
        this.tickets = tickets;
    }

    /**
     * Выписывает пропуск на скачивание собранного архива.
     *
     * <p>Имя объекта начинается с владельца, поэтому проверка права сводится к тому, лежит ли архив
     * под именем обратившегося: чужой архив для него не существует.
     */
    @PostMapping("/{exportId}/ticket")
    public DownloadTicket issueTicket(
            @PathVariable String exportId, @AuthenticationPrincipal Jwt token) {
        String ownerId = token.getSubject();
        exports.requireArchive(ownerId, exportId);
        return tickets.issue(exportId, ownerId);
    }

    /** Отдаёт архив по пропуску — без токена: по такой ссылке переходит браузер. */
    @GetMapping("/{exportId}/download/{ticket}")
    public ResponseEntity<StreamingResponseBody> downloadByTicket(
            @PathVariable String exportId, @PathVariable String ticket) {
        String ownerId =
                tickets.verify(ticket, exportId)
                        .orElseThrow(
                                () ->
                                        new com.bookcase.storage.exception
                                                .StoredFileNotFoundException(exportId));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("bookcase-library.zip", StandardCharsets.UTF_8)
                        .build());
        headers.add("X-Content-Type-Options", "nosniff");
        long size = exports.requireArchive(ownerId, exportId);
        StreamingResponseBody body = output -> exports.streamArchive(ownerId, exportId, output);
        return ResponseEntity.ok().headers(headers).contentLength(size).body(body);
    }

    /** Пачка файлов одним архивом прямо в ответе. */
    @PostMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal Jwt token, @Valid @RequestBody ArchiveRequest request) {
        List<StoredFile> selected = files.findAll(request.files(), token.getSubject());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.attachment()
                        .filename("books.zip", StandardCharsets.UTF_8)
                        .build());
        headers.add("X-Content-Type-Options", "nosniff");

        StreamingResponseBody body = output -> assembler.assemble(selected, output);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Тот же архив, но в хранилище: так собирается фоновый экспорт. */
    @PostMapping
    public ArchiveLocation export(
            @AuthenticationPrincipal Jwt token, @Valid @RequestBody ArchiveRequest request)
            throws IOException {
        String ownerId = token.getSubject();
        return exports.export(
                ownerId,
                java.util.UUID.randomUUID().toString(),
                files.findAll(request.files(), ownerId));
    }
}
