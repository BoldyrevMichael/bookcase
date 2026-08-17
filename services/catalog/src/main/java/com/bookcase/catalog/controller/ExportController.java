package com.bookcase.catalog.controller;

import com.bookcase.catalog.dto.ExportTaskView;
import com.bookcase.catalog.service.ExportService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Выгрузка библиотеки одним архивом.
 *
 * <p>Ответ на просьбу — задача, а не архив: собирать его может быть долго, и держать всё это время
 * открытое соединение незачем.
 */
@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {

    private final ExportService exports;

    public ExportController(ExportService exports) {
        this.exports = exports;
    }

    @PostMapping
    public ResponseEntity<ExportTaskView> request(@AuthenticationPrincipal Jwt token) {
        UUID taskId = exports.request(token.getSubject());
        ExportTaskView task = exports.find(taskId, token.getSubject(), token.getTokenValue());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/exports/" + taskId))
                .body(task);
    }

    @GetMapping("/{id}")
    public ExportTaskView find(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        return exports.find(id, token.getSubject(), token.getTokenValue());
    }
}
