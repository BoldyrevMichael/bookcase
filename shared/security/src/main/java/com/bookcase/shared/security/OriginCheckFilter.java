package com.bookcase.shared.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Второй рубеж против подделки межсайтовых запросов.
 *
 * <p>Первый рубеж — признак {@code SameSite} у cookie сессии: браузер не приложит её к запросу,
 * начатому чужим сайтом. Пока он стоит, подделка невозможна. Но держаться на одном признаке
 * рискованно: его однажды ослабляют ради встраивания или совместимости, и защита исчезает целиком,
 * бесшумно.
 *
 * <p>Поэтому изменяющие запросы проверяются ещё и по заголовку источника. Логика простая: если
 * браузер сообщил, с какой страницы пришёл запрос, и страница чужая — отказ. Если заголовка нет
 * вовсе, запрос пропускается: так приходят программные клиенты, а им подделка межсайтовых запросов
 * не грозит — они не носят с собой cookie и предъявляют токен сами.
 *
 * <p>Чтение не проверяется: подделка опасна изменением, а прочитать ответ чужая страница всё равно
 * не сможет — правил доступа между источниками мы не выдаём.
 */
@Slf4j
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final List<String> allowedOrigins;

    public OriginCheckFilter(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    @SuppressFBWarnings(
            value = "SERVLET_HEADER",
            justification =
                    "Заголовок источника здесь и есть предмет проверки: он сравнивается со "
                            + "списком разрешённых и никак иначе не используется.")
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (SAFE_METHODS.contains(request.getMethod())
                || origin == null
                || allowedOrigins.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        if (allowedOrigins.contains(origin)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn(
                "запрос {} {} отклонён: чужой источник {}",
                request.getMethod(),
                request.getRequestURI(),
                origin);
        // Ответ пишется напрямую, а не через sendError: тот отдаёт управление обработчику
        // отказов Spring Security, и запрет превращается в «войдите» — 401 вместо 403.
        // Разница существенная: пользователь вошёл, дело не в этом.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write(
                        "{\"title\":\"Запрос не принят\","
                                + "\"detail\":\"запрос пришёл с чужой страницы\",\"status\":403}");
    }
}
