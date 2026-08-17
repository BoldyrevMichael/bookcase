package com.bookcase.ingester;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранилище файлов, подменённое на время теста.
 *
 * <p>Настоящее уже проверено собственными тестами, и поднимать его здесь незачем: разбору от него
 * нужны ровно две вещи — выдать пропуск и отдать байты. Подмена делает и то и другое, зато
 * позволяет разложить внутрь ровно те файлы, которые нужны проверке.
 */
final class FakeStorage implements AutoCloseable {

    private static final String TICKET = "тестовый-пропуск";

    /** Тот же адрес, по которому файл отдаёт настоящее хранилище. */
    private static final java.util.regex.Pattern DOWNLOAD_PATH =
            java.util.regex.Pattern.compile("^/api/v1/files/([0-9a-f]{64})/download/[^/]+$");

    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    FakeStorage() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось поднять подменное хранилище", exception);
        }
        server.createContext(
                "/api/v1/files/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.endsWith("/ticket")) {
                        respond(
                                exchange,
                                ("{\"token\":\""
                                                + TICKET
                                                + "\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}")
                                        .getBytes(StandardCharsets.UTF_8),
                                "application/json");
                        return;
                    }
                    // Путь разбирается строго, тем же образом, что и в настоящем хранилище.
                    // Подмена, отвечающая на что угодно, скрыла бы расхождение адресов
                    // между сервисами — а это ровно та ошибка, которую тест должен ловить.
                    java.util.regex.Matcher download = DOWNLOAD_PATH.matcher(path);
                    byte[] content = download.matches() ? files.get(download.group(1)) : null;
                    if (content == null) {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    respond(exchange, content, "application/octet-stream");
                });
        server.start();
    }

    /** Кладёт файл и возвращает его хэш — так же, как это сделало бы настоящее хранилище. */
    String put(byte[] content) {
        String sha256 = hash(content);
        files.put(sha256, content);
        return sha256;
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respond(
            com.sun.net.httpserver.HttpExchange exchange, byte[] body, String contentType)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
