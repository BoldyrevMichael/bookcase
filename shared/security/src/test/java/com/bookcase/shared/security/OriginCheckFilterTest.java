package com.bookcase.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Проверки второго рубежа против подделки межсайтовых запросов.
 *
 * <p>Первый рубеж — признак SameSite у cookie сессии; этот нужен на случай, когда его однажды
 * ослабят ради встраивания или совместимости и защита исчезнет бесшумно.
 */
class OriginCheckFilterTest {

    private static final List<String> ALLOWED = List.of("http://localhost:8080");

    private MockHttpServletResponse send(String method, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/books/1");
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        new OriginCheckFilter(ALLOWED).doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("изменение со своей страницы проходит")
    void allowsOwnOrigin() throws Exception {
        assertThat(send("POST", "http://localhost:8080").getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("изменение с чужой страницы отвергается")
    void rejectsForeignOrigin() throws Exception {
        assertThat(send("DELETE", "http://evil.example").getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("клиент без источника проходит: подделка межсайтовых запросов ему не грозит")
    void allowsRequestWithoutOrigin() throws Exception {
        assertThat(send("POST", null).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("чтение не проверяется: ответ чужой странице всё равно не прочесть")
    void doesNotCheckReads() throws Exception {
        assertThat(send("GET", "http://evil.example").getStatus()).isEqualTo(200);
    }
}
