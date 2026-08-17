package com.bookcase.catalog.controller;

import com.bookcase.catalog.dto.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class MeController {

    /**
     * Кто пришёл. Отвечает на единственный вопрос, который сервис может решить, ещё не имея данных:
     * дошёл ли до него разобранный токен и кем этот токен выдан.
     *
     * <p>Все три поля берутся из токена. Ни тело запроса, ни заголовки на них не влияют.
     */
    @GetMapping("/me")
    public CurrentUser currentUser(@AuthenticationPrincipal Jwt token) {
        return new CurrentUser(
                token.getSubject(),
                token.getClaimAsString("preferred_username"),
                token.getClaimAsString("email"));
    }
}
