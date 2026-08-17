package com.bookcase.catalog.controller;

import com.bookcase.catalog.dto.Facets;
import com.bookcase.catalog.repository.ThemeRepository;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Темы библиотеки.
 *
 * <p>Список нужен не только для показа: из него берутся подсказки при вводе, и благодаря им в
 * библиотеке не заводятся java, Java и джава по отдельности.
 */
@RestController
@RequestMapping("/api/v1/themes")
public class ThemeController {

    private final ThemeRepository themes;

    public ThemeController(ThemeRepository themes) {
        this.themes = themes;
    }

    @GetMapping
    public List<Facets.FacetValue> findAll(@AuthenticationPrincipal Jwt token) {
        return themes.findAll(token.getSubject());
    }
}
