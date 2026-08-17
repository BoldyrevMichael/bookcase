package com.bookcase.storage.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Скачивание по подписанному пропуску.
 *
 * <p>Такая ссылка открывается обычным переходом, без заголовка с токеном, — иначе адрес собранного
 * архива нельзя было бы просто отдать пользователю. Поэтому запрос с пропуском проходит проверку
 * токена мимо: право на файл подтверждает сам пропуск, а его подлинность, срок и то, на что он
 * выписан, разбирает сервис.
 *
 * <p>Пропуск стоит отрезком пути, а не параметром запроса. Это не украшение: шлюз перед сервисами
 * решает, пускать ли запрос без входа, глядя на путь, — параметров он при этом не смотрит, и ссылка
 * с пропуском в параметре до сервиса просто не дошла бы.
 *
 * <p>Дополнение к общим правилам, а не замена: всё остальное по-прежнему требует токена.
 */
@Configuration
public class TicketDownloadSecurityConfig {

    private static final Pattern TICKET_DOWNLOAD =
            Pattern.compile("^/api/v1/(files|archives)/[^/]+/download/[^/]+$");

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain ticketDownloadSecurity(HttpSecurity http) {
        return http.securityMatcher(TicketDownloadSecurityConfig::isTicketDownload)
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    private static boolean isTicketDownload(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && TICKET_DOWNLOAD.matcher(request.getRequestURI()).matches();
    }
}
