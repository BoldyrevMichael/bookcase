package com.bookcase.catalog.controller;

import com.bookcase.catalog.config.CatalogProperties;
import com.bookcase.catalog.dto.BookCard;
import com.bookcase.catalog.dto.BookEdit;
import com.bookcase.catalog.dto.BookPage;
import com.bookcase.catalog.dto.BookSearchRequest;
import com.bookcase.catalog.dto.DownloadLink;
import com.bookcase.catalog.service.BookService;
import com.bookcase.catalog.service.DownloadService;
import com.bookcase.catalog.service.LibraryService;
import com.bookcase.catalog.service.state.Shelf;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Книги: поиск, карточка, правка, полка, избранное, удаление и ссылка на скачивание. */
@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService books;
    private final LibraryService library;
    private final DownloadService downloads;
    private final CatalogProperties properties;

    public BookController(
            BookService books,
            LibraryService library,
            DownloadService downloads,
            CatalogProperties properties) {
        this.books = books;
        this.library = library;
        this.downloads = downloads;
        this.properties = properties;
    }

    /**
     * Поиск по библиотеке.
     *
     * <p>Параметр «где искать» задан сразу, хотя значение у него пока одно: когда появится поиск по
     * содержимому книг, запросы существующих клиентов останутся верными.
     */
    @GetMapping
    public BookPage search(
            @AuthenticationPrincipal Jwt token, @ModelAttribute BookSearchRequest request) {
        return books.search(request.toQuery(pageSize(request.limit())), token.getSubject());
    }

    @GetMapping("/{id}")
    public BookCard find(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        return books.find(id, token.getSubject());
    }

    @PatchMapping("/{id}")
    public BookCard edit(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt token,
            @Valid @RequestBody BookEdit edit) {
        return books.edit(id, token.getSubject(), edit);
    }

    @PutMapping("/{id}/shelf")
    public BookCard setShelf(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt token, @RequestParam Shelf value) {
        books.setShelf(id, token.getSubject(), value);
        return books.find(id, token.getSubject());
    }

    @PutMapping("/{id}/favorite")
    public BookCard setFavorite(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt token,
            @RequestParam boolean value) {
        books.setFavorite(id, token.getSubject(), value);
        return books.find(id, token.getSubject());
    }

    /** Убирает книгу из библиотеки и сообщает хранилищу, что файл больше не нужен владельцу. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        library.remove(id, token.getSubject(), token.getTokenValue());
    }

    /**
     * Обложка книги.
     *
     * <p>Отдаётся по идентификатору карточки, а не по адресу картинки в хранилище: так владение
     * проверяется до выдачи, и хэш обложки — по которому её можно было бы запросить напрямую —
     * наружу не попадает вовсе.
     */
    @GetMapping(value = "/{id}/cover", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> cover(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        byte[] image = downloads.coverOf(id, token.getSubject(), token.getTokenValue());
        return ResponseEntity.ok()
                // Содержимое меняться не может: другая обложка — это другая карточка.
                // Кэш личный: ответ выдан вошедшему.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .body(image);
    }

    /**
     * Уточнить книгу заново.
     *
     * <p>Ответ приходит не сразу: справочники спрашивает фоновый работник, и минутами позже
     * карточка дополнится сама. Поэтому здесь «принято к исполнению», а не «сделано».
     */
    @PostMapping("/{id}/enrich")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void enrich(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        library.requestEnrichment(id, token.getSubject());
    }

    /** Ссылка на скачивание: имя файла собирается по нынешней схеме, ссылка живёт недолго. */
    @GetMapping("/{id}/download")
    public DownloadLink download(@PathVariable UUID id, @AuthenticationPrincipal Jwt token) {
        return downloads.forBook(id, token.getSubject(), token.getTokenValue());
    }

    private int pageSize(Integer requested) {
        if (requested == null || requested <= 0) {
            return properties.defaultPageSize();
        }
        return Math.min(requested, properties.maxPageSize());
    }
}
