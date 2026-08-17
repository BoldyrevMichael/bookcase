package com.bookcase.catalog;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Хранилище файлов, подменённое на время теста.
 *
 * <p>Каталогу от него нужны две вещи: выписать пропуск на скачивание и принять к сведению, что
 * владелец расстался с книгой. Настоящее хранилище проверено своими тестами, и поднимать его здесь
 * значило бы проверять его второй раз.
 */
final class FakeStorage implements AutoCloseable {

    /** Содержимое обложки, которое подмена отдаёт на любой запрос картинки. */
    static final byte[] COVER_IMAGE =
            "обложка книги, байты которой можно сверить".getBytes(StandardCharsets.UTF_8);

    private final HttpServer server;
    private final List<String> releasedFiles = Collections.synchronizedList(new ArrayList<>());
    private final List<String> releasedCovers = Collections.synchronizedList(new ArrayList<>());

    FakeStorage() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось поднять подменное хранилище", exception);
        }
        server.createContext(
                "/api/v1/files/",
                exchange -> {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        String path = exchange.getRequestURI().getPath();
                        releasedFiles.add(path.substring(path.lastIndexOf('/') + 1));
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                        return;
                    }
                    respondWithTicket(exchange);
                });
        server.createContext("/api/v1/archives/", FakeStorage::respondWithTicket);
        // Обложку каталог проводит через себя, поэтому в тесте важно, что он её действительно
        // запрашивает у хранилища и отдаёт дальше без изменений.
        server.createContext(
                "/api/v1/covers/",
                exchange -> {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        String path = exchange.getRequestURI().getPath();
                        releasedCovers.add(path.substring(path.lastIndexOf('/') + 1));
                        exchange.sendResponseHeaders(204, -1);
                        exchange.close();
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
                    exchange.sendResponseHeaders(200, COVER_IMAGE.length);
                    exchange.getResponseBody().write(COVER_IMAGE);
                    exchange.close();
                });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Карточки, о которых каталог сообщил, что обложка им больше не нужна. */
    List<String> releasedCovers() {
        return List.copyOf(releasedCovers);
    }

    /** Файлы, о которых каталог сообщил, что они больше не нужны владельцу. */
    List<String> releasedFiles() {
        return List.copyOf(releasedFiles);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respondWithTicket(HttpExchange exchange) throws IOException {
        byte[] body =
                "{\"token\":\"тестовый-пропуск\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}"
                        .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
