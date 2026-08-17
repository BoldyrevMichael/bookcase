package com.bookcase.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Проверка токена на входе в сервис.
 *
 * <p>Ключи для подписи выпускаются здесь же, а отдаёт их поднятый на время теста крошечный
 * http-сервер — так проверяется ровно то, что делает сервис: забирает ключи по адресу из настроек и
 * сверяет с ними подпись, издателя, получателя и срок. Настоящий Keycloak для этого не нужен, а
 * перебрать на нём испорченные токены было бы нечем.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeEndpointSecurityTest {

    // База проверке токена не нужна, но сервис поднимается целиком и без неё не стартует.
    // Свой контейнер здесь потому, что тест не должен зависеть от того, поднят ли рядом стенд:
    // такая зависимость однажды уже сделала прогон зелёным по случайности.
    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    private static final String ISSUER = "http://keycloak.test/realms/bookcase";
    private static final String AUDIENCE = "bookcase-api";
    private static final String SUBJECT = "4a1f0f4e-2b2b-4a4e-9a3e-3d7f2c1b0a55";
    private static final Duration VALID = Duration.ofMinutes(15);

    private static final RSAKey SIGNING_KEY = generateKey("signing");
    private static final RSAKey FOREIGN_KEY = generateKey("foreign");
    private static final HttpServer JWK_SET_SERVER = startJwkSetServer();

    @LocalServerPort private int port;

    @LocalManagementPort private int managementPort;

    private RestTestClient client;

    @DynamicPropertySource
    static void oidcSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        String jwkSetUri = "http://localhost:" + JWK_SET_SERVER.getAddress().getPort() + "/certs";
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
        // В application.yml служебный порт задан постоянным числом; на машине сборки он занят.
        registry.add("management.server.port", () -> "0");
    }

    @AfterAll
    static void stopJwkSetServer() {
        JWK_SET_SERVER.stop(0);
    }

    @BeforeEach
    void createClient() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("действительный токен: сервис отвечает и берёт владельца из токена")
    void validTokenIsAccepted() {
        callMe(token(SIGNING_KEY, ISSUER, AUDIENCE, Instant.now().plus(VALID)))
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(
                        body ->
                                assertThat(body)
                                        .contains("\"id\":\"" + SUBJECT + "\"")
                                        .contains("\"username\":\"reader\"")
                                        .contains("\"email\":\"reader@bookcase.test\""));
    }

    @Test
    @DisplayName("без токена — 401")
    void requestWithoutTokenIsRejected() {
        client.get().uri("/api/v1/me").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("токен, выписанный другой системе, — 401")
    void tokenForAnotherAudienceIsRejected() {
        callMe(token(SIGNING_KEY, ISSUER, "another-system", Instant.now().plus(VALID)))
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("токен чужого издателя — 401")
    void tokenFromAnotherIssuerIsRejected() {
        callMe(
                        token(
                                SIGNING_KEY,
                                "http://evil.test/realms/bookcase",
                                AUDIENCE,
                                Instant.now().plus(VALID)))
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("просроченный токен — 401")
    void expiredTokenIsRejected() {
        callMe(token(SIGNING_KEY, ISSUER, AUDIENCE, Instant.now().minus(Duration.ofMinutes(10))))
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("токен, подписанный чужим ключом, — 401")
    void tokenSignedByAnotherKeyIsRejected() {
        callMe(token(FOREIGN_KEY, ISSUER, AUDIENCE, Instant.now().plus(VALID)))
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    @DisplayName("проба готовности на служебном порту отвечает без токена")
    void probesAreOpen() {
        RestTestClient management =
                RestTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();

        management
                .get()
                .uri("/actuator/health/readiness")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("UP"));
    }

    private RestTestClient.ResponseSpec callMe(String token) {
        return client.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private static String token(RSAKey key, String issuer, String audience, Instant expiresAt) {
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject(SUBJECT)
                        .issuer(issuer)
                        .audience(audience)
                        .issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
                        .expirationTime(Date.from(expiresAt))
                        .claim("preferred_username", "reader")
                        .claim("email", "reader@bookcase.test")
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                        claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException exception) {
            throw new IllegalStateException("не удалось подписать тестовый токен", exception);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("не удалось выпустить тестовый ключ", exception);
        }
    }

    private static HttpServer startJwkSetServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwkSet =
                    new JWKSet(SIGNING_KEY.toPublicJWK())
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
            server.createContext(
                    "/certs",
                    exchange -> {
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, jwkSet.length);
                        exchange.getResponseBody().write(jwkSet);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось поднять сервер ключей", exception);
        }
    }
}
