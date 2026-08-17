package com.bookcase.enricher.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * Обращения к хранилищу от собственного имени сервиса.
 *
 * <p>Обложка приходит в фоне, спустя часы после того, как книгу загрузили, и токена пользователя к
 * этому времени давно нет. Способ, которым это решено у разбора файлов, — подписанный пропуск,
 * выписанный заранее, — здесь не годится: пропуск даёт право на один известный файл, а обложка на
 * момент загрузки ещё не существует.
 *
 * <p>Поэтому у уточнителя своя учётная запись в Keycloak, и токен он получает по
 * client_credentials. Права она даёт ровно одно — класть обложки; читать чужие книги ею нельзя.
 */
@Configuration
public class StorageClientConfig {

    /** Имя настройки клиента: под ним же он описан в application.yml. */
    public static final String REGISTRATION_ID = "storage";

    /**
     * Кто добывает и обновляет токен.
     *
     * <p>Служебный вариант хранилища выданных токенов, а не привязанный к сессии пользователя: у
     * фоновой работы нет ни сессии, ни запроса, в рамках которого она идёт.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /** Клиент к хранилищу: токен подставляется перехватчиком, сам код о нём не знает. */
    @Bean
    public RestClient storageRestClient(
            EnricherProperties properties, OAuth2AuthorizedClientManager clientManager) {
        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(clientManager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        return RestClient.builder()
                .baseUrl(properties.storageUrl())
                .requestInterceptor(interceptor)
                .build();
    }
}
