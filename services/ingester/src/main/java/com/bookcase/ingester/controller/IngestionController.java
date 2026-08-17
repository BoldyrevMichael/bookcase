package com.bookcase.ingester.controller;

import com.bookcase.ingester.dto.IngestionRequest;
import com.bookcase.ingester.dto.IngestionTaskView;
import com.bookcase.ingester.service.IngestionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Задачи разбора.
 *
 * <p>Файл к этому времени уже лежит в хранилище: сюда приходит только его хэш. Разбор идёт в фоне,
 * поэтому ответ на просьбу — не результат, а задача, за состоянием которой можно следить.
 */
@RestController
@RequestMapping("/api/v1/ingestions")
public class IngestionController {

    private final IngestionService ingestions;

    public IngestionController(IngestionService ingestions) {
        this.ingestions = ingestions;
    }

    @PostMapping
    public ResponseEntity<IngestionTaskView> request(
            @AuthenticationPrincipal Jwt token, @Valid @RequestBody IngestionRequest request) {
        UUID taskId = ingestions.request(token.getSubject(), token.getTokenValue(), request);
        IngestionTaskView task = ingestions.find(taskId, token.getSubject());
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ingestions/" + taskId))
                .body(task);
    }

    @GetMapping("/{taskId}")
    public IngestionTaskView find(@PathVariable UUID taskId, @AuthenticationPrincipal Jwt token) {
        return ingestions.find(taskId, token.getSubject());
    }

    @GetMapping
    public List<IngestionTaskView> findAll(@AuthenticationPrincipal Jwt token) {
        return ingestions.findAll(token.getSubject());
    }
}
