package com.bookcase.catalog.controller;

import com.bookcase.catalog.dto.CollectionView;
import com.bookcase.catalog.service.CollectionService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Подборки: зачем книги собраны вместе. */
@Validated
@RestController
@RequestMapping("/api/v1/collections")
public class CollectionController {

    private final CollectionService collections;

    public CollectionController(CollectionService collections) {
        this.collections = collections;
    }

    @GetMapping
    public List<CollectionView> findAll(@AuthenticationPrincipal Jwt token) {
        return collections.findAll(token.getSubject());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionView create(
            @AuthenticationPrincipal Jwt token, @Validated @RequestBody NewCollection request) {
        UUID id = collections.create(token.getSubject(), request.name());
        return collections.findAll(token.getSubject()).stream()
                .filter(collection -> collection.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        collections.delete(id, token.getSubject());
    }

    @PutMapping("/{id}/books/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addBook(
            @PathVariable UUID id, @PathVariable UUID bookId, @AuthenticationPrincipal Jwt token) {
        collections.addBook(id, bookId, token.getSubject());
    }

    @DeleteMapping("/{id}/books/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeBook(
            @PathVariable UUID id, @PathVariable UUID bookId, @AuthenticationPrincipal Jwt token) {
        collections.removeBook(id, bookId, token.getSubject());
    }

    /**
     * Новая подборка.
     *
     * @param name имя, данное человеком
     */
    public record NewCollection(@NotBlank @Size(max = 200) String name) {}
}
