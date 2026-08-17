package com.bookcase.enricher.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.enricher.config.EnricherProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Проверки скачивания обложки.
 *
 * <p>Все случаи взяты из живого прогона: справочники охотно отдают вместо обложки что-нибудь
 * другое, и выглядит это как успех — приходит настоящая картинка с кодом 200.
 */
class CoverDownloaderTest {

    private static HttpServer server;

    private final CoverDownloader downloader =
            new CoverDownloader(
                    new EnricherProperties(
                            new EnricherProperties.Google("", "", 1000),
                            new EnricherProperties.OpenLibrary("", "", true),
                            null,
                            10,
                            5,
                            null,
                            null,
                            new EnricherProperties.Match(0.5, 0.8, 30),
                            "http://localhost",
                            DataSize.ofMegabytes(1),
                            java.time.Duration.ofSeconds(2),
                            java.time.Duration.ofSeconds(3)),
                    new org.springframework.http.client.JdkClientHttpRequestFactory());

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/cover.jpg", exchange -> respond(exchange, "image/jpeg", 20_000));
        // Так отвечает Open Library на книгу без обложки: картинка есть, а обложки нет.
        server.createContext("/empty.jpg", exchange -> respond(exchange, "image/jpeg", 9));
        server.createContext("/huge.jpg", exchange -> respond(exchange, "image/jpeg", 2_000_000));
        server.createContext("/page.html", exchange -> respond(exchange, "text/html", 5_000));
        server.createContext(
                "/missing.jpg",
                exchange -> {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, String contentType, int size)
            throws IOException {
        byte[] body = new byte[size];
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Test
    @DisplayName("обычная обложка забирается")
    void downloadsCover() {
        assertThat(downloader.download(url("/cover.jpg")))
                .isPresent()
                .get()
                .satisfies(cover -> assertThat(cover.content()).hasSize(20_000));
    }

    @Test
    @DisplayName("пустышка в несколько байт обложкой не считается")
    void rejectsPlaceholder() {
        // Ровно на этом обожглись: девятибайтовый ответ прошёл как обложка и достался
        // сразу нескольким книгам.
        assertThat(downloader.download(url("/empty.jpg"))).isEmpty();
    }

    @Test
    @DisplayName("слишком большое изображение не берётся")
    void rejectsHugeImage() {
        assertThat(downloader.download(url("/huge.jpg"))).isEmpty();
    }

    @Test
    @DisplayName("страница вместо картинки не берётся")
    void rejectsNonImage() {
        assertThat(downloader.download(url("/page.html"))).isEmpty();
    }

    @Test
    @DisplayName("отсутствующая обложка не ломает уточнение")
    void survivesMissingCover() {
        assertThat(downloader.download(url("/missing.jpg"))).isEmpty();
        assertThat(downloader.download(null)).isEmpty();
    }

    @Test
    @DisplayName("байты обложки наружу отдаются копией")
    void contentIsCopied() {
        CoverDownloader.Cover cover =
                new CoverDownloader.Cover(
                        "картинка".getBytes(StandardCharsets.UTF_8), "image/jpeg");
        byte[] first = cover.content();
        first[0] = 0;

        assertThat(cover.content()).isNotEqualTo(first);
    }
}
