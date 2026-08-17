package com.bookcase.shared.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Единая проверка токена для всех сервисов.
 *
 * <p>Настройка живёт в общем модуле не ради экономии строк: расхождение здесь — это дыра, а не
 * несогласованность стиля. Сервису остаётся указать издателя, адрес ключей и получателя.
 *
 * <p>Токен проверяется в самом сервисе: подпись — по ключам издателя, издатель, получатель и срок —
 * правилами из настроек. Шлюз перед сервисом ничего из этого не заменяет, и то, что он проставляет
 * в заголовки, для сервиса — обычные данные от клиента.
 */
@AutoConfiguration
@EnableWebSecurity
@EnableConfigurationProperties(SharedSecurityProperties.class)
public class ResourceServerSecurityAutoConfiguration {

    /** Заголовок, которым шлюз передаёт наверх токен доступа вошедшего пользователя. */
    private static final String FORWARDED_ACCESS_TOKEN = "X-Forwarded-Access-Token";

    /**
     * Служебные точки — пробы, сведения о сборке, метрики — открыты. Они живут на отдельном порту,
     * который наружу не публикуется, и нужны тому, кто следит за сервисом, а не пользователю.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean(name = "actuatorSecurity")
    public SecurityFilterChain actuatorSecurity(HttpSecurity http) {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Всё остальное — только с действительным токеном.
     *
     * <p>Сервис, которому нужны собственные исключения из этого правила, объявляет свою цепочку с
     * именем {@code apiSecurity} — тогда эта не создаётся.
     */
    @Bean
    @ConditionalOnMissingBean(name = "apiSecurity")
    public SecurityFilterChain apiSecurity(
            HttpSecurity http,
            BearerTokenResolver tokenResolver,
            SharedSecurityProperties settings) {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                // Проверка источника изменяющих запросов — вторым рубежом к признаку
                // SameSite у cookie сессии. Стоит до проверки токена: чужой странице
                // незачем добираться даже до разбора заголовков.
                .addFilterBefore(
                        new OriginCheckFilter(settings.allowedOrigins()),
                        org.springframework.security.web.authentication.preauth
                                .AbstractPreAuthenticatedProcessingFilter.class)
                .oauth2ResourceServer(
                        server ->
                                server.bearerTokenResolver(tokenResolver)
                                        .jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                RealmRolesConverter.create())))
                // Сервис не заводит сессий: состояние входа держит шлюз, сюда приходит токен.
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Защита от подделки межсайтовых запросов нужна там, где браузер сам прикладывает
                // cookie. Здесь запрос без токена не проходит вовсе.
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .build();
    }

    /**
     * Где искать токен.
     *
     * <p>Программный клиент приносит его в заголовке {@code Authorization} — обычным образом.
     * Браузер токенов не держит вовсе: его запрос идёт через шлюз, и токен доступа тот прикладывает
     * своим заголовком. В {@code Authorization} шлюз кладёт ID-токен, выписанный ему самому, — для
     * обращения к API он не предназначен.
     */
    @Bean
    @ConditionalOnMissingBean(BearerTokenResolver.class)
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
        return request -> resolveToken(standard, request);
    }

    @SuppressFBWarnings(
            value = "SERVLET_HEADER",
            justification =
                    "Заголовок указывает лишь, где лежит токен. Кто пользователь, решает "
                            + "не он, а разбор самого токена: подпись, издатель, получатель и срок "
                            + "проверяются дальше в любом случае.")
    private static String resolveToken(BearerTokenResolver standard, HttpServletRequest request) {
        String token = standard.resolve(request);
        return token != null ? token : request.getHeader(FORWARDED_ACCESS_TOKEN);
    }
}
