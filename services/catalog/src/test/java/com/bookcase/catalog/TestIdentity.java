package com.bookcase.catalog;

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
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Токены для тестов: ключ выпускается на месте, а отдаёт его поднятый здесь же крошечный
 * http-сервер. Настоящий Keycloak для проверки разбора книг не нужен.
 */
final class TestIdentity {

    static final String ISSUER = "http://keycloak.test/realms/bookcase";
    static final String AUDIENCE = "bookcase-api";

    private static final RSAKey KEY = generateKey();
    private static final HttpServer JWK_SET_SERVER = startJwkSetServer();

    private TestIdentity() {}

    static void registerProperties(DynamicPropertyRegistry registry) {
        String jwkSetUri = "http://localhost:" + JWK_SET_SERVER.getAddress().getPort() + "/certs";
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> jwkSetUri);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
        registry.add("management.server.port", () -> "0");
    }

    static String tokenFor(String ownerId) {
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .subject(ownerId)
                        .issuer(ISSUER)
                        .audience(AUDIENCE)
                        .issueTime(Date.from(Instant.now().minus(Duration.ofMinutes(1))))
                        .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(30))))
                        .claim("preferred_username", ownerId)
                        .build();
        SignedJWT jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
                        claims);
        try {
            jwt.sign(new RSASSASigner(KEY));
        } catch (JOSEException exception) {
            throw new IllegalStateException("не удалось подписать тестовый токен", exception);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test").generate();
        } catch (JOSEException exception) {
            throw new IllegalStateException("не удалось выпустить тестовый ключ", exception);
        }
    }

    private static HttpServer startJwkSetServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            byte[] jwkSet =
                    new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
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
