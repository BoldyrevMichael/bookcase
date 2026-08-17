package com.bookcase.ingester;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.events.MetadataSource;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Разбор от просьбы до готовой карточки.
 *
 * <p>Проверка идёт через настоящую очередь и настоящую базу: разбор здесь именно тем и является,
 * что происходит не сразу и в другом месте, и подделка очереди проверяла бы не то.
 *
 * <p>Пять форматов дают пять карточек, и видно, откуда что взялось: у книг с метаданными внутри
 * поля приходят из файла, у скана — только из имени файла, потому что больше взять их неоткуда.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionFlowTest {

    private static final String READER = "11111111-1111-1111-1111-111111111111";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final FakeStorage STORAGE = new FakeStorage();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    @Container private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @LocalServerPort private int port;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestIdentity.registerProperties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("bookcase.ingester.storage-url", STORAGE::url);
        // Повторять здесь нечего: подменное хранилище всегда на месте.
        registry.add("bookcase.ingester.retry-attempts", () -> "1");
    }

    @AfterAll
    static void stopStorage() {
        STORAGE.close();
    }

    @Test
    @DisplayName("EPUB: всё берётся из самого файла")
    void epubIsIngested() {
        Map<String, Object> metadata =
                ingest(TestBooks.epub(), "Швец А. - Погружение в паттерны (2018).epub");

        assertThat(metadata)
                .containsEntry("format", "EPUB")
                .containsEntry("title", "Погружение в паттерны проектирования")
                .containsEntry("authors", java.util.List.of("Швец А."))
                .containsEntry("year", 2018)
                .containsEntry("language", "ru")
                .containsEntry("isbn", "9785932861530")
                .containsEntry("series", "Паттерны")
                .containsEntry("seriesNumber", 2);
        assertThat(sources(metadata)).containsEntry("title", MetadataSource.EMBEDDED.name());
    }

    @Test
    @DisplayName("FB2: имя автора собрано из частей и переставлено")
    void fb2IsIngested() {
        Map<String, Object> metadata = ingest(TestBooks.fb2(), "Стругацкий - Понедельник.fb2");

        assertThat(metadata)
                .containsEntry("format", "FB2")
                .containsEntry("title", "Понедельник начинается в субботу")
                .containsEntry("authors", java.util.List.of("Стругацкий А."))
                .containsEntry("year", 1965)
                .containsEntry("isbn", "9785171183660");
    }

    @Test
    @DisplayName("PDF: название и автор из сведений о документе")
    void pdfIsIngested() {
        Map<String, Object> metadata = ingest(TestBooks.pdf(), "Макконнелл - Совершенный код.pdf");

        assertThat(metadata)
                .containsEntry("format", "PDF")
                .containsEntry("title", "Совершенный код")
                .containsEntry("authors", java.util.List.of("McConnell S."))
                .containsEntry("year", 2010);
    }

    @Test
    @DisplayName("DJVU: метаданных в файле нет, всё берётся из имени")
    void djvuFallsBackToFilename() {
        Map<String, Object> metadata =
                ingest(TestBooks.djvu(), "Владимиров С.М. - Криптография - 2005.djvu");

        assertThat(metadata)
                .containsEntry("format", "DJVU")
                .containsEntry("title", "Криптография")
                .containsEntry("authors", java.util.List.of("Владимиров С. М."))
                .containsEntry("year", 2005);
        assertThat(sources(metadata)).containsEntry("title", MetadataSource.FILENAME.name());
        assertThat(sources(metadata)).containsEntry("authors", MetadataSource.FILENAME.name());
    }

    @Test
    @DisplayName("текст: разметка заголовка вместо метаданных")
    void textIsIngested() {
        Map<String, Object> metadata = ingest(TestBooks.gutenbergText(), "carroll-alice.txt");

        assertThat(metadata)
                .containsEntry("format", "TXT")
                .containsEntry("title", "Alice's Adventures in Wonderland")
                .containsEntry("authors", java.util.List.of("Carroll L."))
                .containsEntry("language", "en")
                .containsEntry("year", 1865);
    }

    @Test
    @DisplayName("документ Word: отказ с причиной, а не бесконечные попытки")
    void wordDocumentIsRejected() {
        Map<String, Object> task =
                waitForCompletion(request(TestBooks.wordDocument(), "Лекции по моделированию.doc"));

        assertThat(task).containsEntry("status", "FAILED");
        assertThat((String) task.get("failureReason")).contains("Word");
        assertThat(task.get("metadata")).isNull();
    }

    @Test
    @DisplayName("чужая задача не видна")
    void foreignTaskIsNotFound() {
        String taskId = (String) request(TestBooks.fb2(), "Стругацкий - Понедельник.fb2").get("id");

        client().get()
                .uri("/api/v1/ingestions/" + taskId)
                .header(HttpHeaders.AUTHORIZATION, bearer("22222222-2222-2222-2222-222222222222"))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    private Map<String, Object> ingest(byte[] content, String originalName) {
        Map<String, Object> task = waitForCompletion(request(content, originalName));

        assertThat(task).as("состояние задачи %s", task).containsEntry("status", "SUCCEEDED");
        return (Map<String, Object>) task.get("metadata");
    }

    private Map<String, Object> request(byte[] content, String originalName) {
        String sha256 = STORAGE.put(content);
        return client().post()
                .uri("/api/v1/ingestions")
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("sha256", sha256, "originalName", originalName))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private Map<String, Object> waitForCompletion(Map<String, Object> task) {
        String taskId = (String) task.get("id");
        Awaitility.await()
                .atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> !java.util.List.of("QUEUED", "RUNNING").contains(status(taskId)));
        return fetch(taskId);
    }

    private String status(String taskId) {
        return (String) fetch(taskId).get("status");
    }

    private Map<String, Object> fetch(String taskId) {
        return client().get()
                .uri("/api/v1/ingestions/" + taskId)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> sources(Map<String, Object> metadata) {
        return (Map<String, String>) metadata.get("sources");
    }

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String bearer(String ownerId) {
        return "Bearer " + TestIdentity.tokenFor(ownerId);
    }
}
