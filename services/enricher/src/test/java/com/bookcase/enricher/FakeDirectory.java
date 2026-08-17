package com.bookcase.enricher;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Внешний справочник, подменённый на время теста.
 *
 * <p>Отвечает по тем же адресам, что и настоящий Open Library, и разбирает запрос так же строго:
 * подмена, отвечающая на что угодно, скрыла бы расхождение в том, как строится запрос, — а это одна
 * из тех ошибок, ради которых тест и написан.
 *
 * <p>Считает обращения. Кэш проверяется именно счётчиком: «второй раз в сеть не пошли» иначе никак
 * не увидеть.
 */
final class FakeDirectory implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean broken;

    FakeDirectory() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException exception) {
            throw new UncheckedIOException("не удалось поднять подменный справочник", exception);
        }
        server.createContext(
                "/search.json",
                exchange -> {
                    calls.incrementAndGet();
                    if (broken) {
                        exchange.sendResponseHeaders(503, -1);
                        exchange.close();
                        return;
                    }
                    String query = query(exchange.getRequestURI().getQuery());
                    String body =
                            answers.getOrDefault(
                                    query.toLowerCase(java.util.Locale.ROOT), "{\"docs\":[]}");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    /** Что справочник ответит на запрос с таким текстом. */
    void answer(String query, String title, List<String> authors, int year) {
        String docs =
                """
                {"docs":[{"title":"%s","author_name":[%s],"first_publish_year":%d,\
                "publisher":["O'Reilly"],"language":["eng"],"isbn":["9780000000001"],\
                "subject":["Computers"],"cover_i":42}]}"""
                        .formatted(
                                title,
                                authors.stream()
                                        .map(a -> "\"" + a + "\"")
                                        .reduce((a, b) -> a + "," + b)
                                        .orElse(""),
                                year);
        answers.put(query.toLowerCase(java.util.Locale.ROOT), docs);
    }

    /** Справочник перестаёт отвечать: так проверяется, что задача откладывается, а не теряется. */
    void breakDown() {
        broken = true;
    }

    /** Справочник снова отвечает. */
    void repair() {
        broken = false;
    }

    int calls() {
        return calls.get();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private String query(String rawQuery) {
        if (rawQuery == null) {
            return "";
        }
        for (String part : rawQuery.split("&")) {
            if (part.startsWith("q=")) {
                return java.net.URLDecoder.decode(part.substring(2), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
