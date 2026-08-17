package com.bookcase.enricher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.bookcase.enricher.client.StorageClient;
import com.bookcase.enricher.service.EnrichmentWorker;
import com.bookcase.events.BookAdded;
import com.bookcase.events.BookDeleted;
import com.bookcase.events.BookEnriched;
import com.bookcase.events.BookEnrichmentRequested;
import com.bookcase.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Уточнение от появления книги до отправленной находки.
 *
 * <p>Проверяется настоящая цепочка: событие из очереди заводит задачу, фоновый работник спрашивает
 * справочник и отправляет находку. Подделывать здесь нечего — отложенность работы и есть предмет
 * проверки.
 */
@Testcontainers
@SpringBootTest
class EnrichmentFlowTest {

    private static final String OWNER = "11111111-1111-1111-1111-111111111111";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final FakeDirectory DIRECTORY = new FakeDirectory();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    @Container private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Autowired private KafkaTemplate<Object, Object> kafka;

    /**
     * Хранилище подменено: проверяется поведение уточнителя при удалении книги, а не сеть. Заодно
     * это единственный способ посмотреть, что происходит, когда хранилище отвечает отказом.
     */
    @MockitoBean private StorageClient storage;

    @Autowired private JdbcClient jdbc;
    @Autowired private EnrichmentWorker worker;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("bookcase.enricher.open-library.base-url", DIRECTORY::url);
        // И обложки берутся у подмены: проверка не должна зависеть от того, отвечает ли
        // сегодня чужая служба картинок.
        registry.add("bookcase.enricher.open-library.covers-url", DIRECTORY::url);
        // Пределы ожидания короткие: в проверке нечему висеть, а если что-то повиснет,
        // это должно кончиться быстро и заметно.
        registry.add("bookcase.enricher.connect-timeout", () -> "2s");
        registry.add("bookcase.enricher.read-timeout", () -> "3s");
        // Ключа нет — Google Books выключен, как это и происходит без ключа в жизни.
        registry.add("bookcase.enricher.google.api-key", () -> "");
        // Работник в тесте запускается вручную, чтобы проверка не зависела от попадания в такт.
        registry.add("bookcase.enricher.poll-interval", () -> "1h");
    }

    @AfterAll
    static void stopDirectory() {
        DIRECTORY.close();
    }

    @Test
    @DisplayName("Книга без метаданных получает название и автора из справочника")
    void enrichesBook() {
        DIRECTORY.answer(
                "Kubernetes Book Poulton", "The Kubernetes Book", List.of("Nigel Poulton"), 2017);
        UUID bookId = announce("Kubernetes Book", List.of("Poulton N."), null, null);

        worker.processDue();

        BookEnriched found = awaitEnriched(bookId);
        assertThat(found.title()).isEqualTo("The Kubernetes Book");
        assertThat(found.authors()).containsExactly("Nigel Poulton");
        assertThat(found.year()).isEqualTo(2017);
        assertThat(found.provider()).isEqualTo("openlibrary");
        assertThat(status(bookId)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("Посторонняя книга не принимается и в каталог не уходит")
    void rejectsWrongCandidate() {
        // Ровно так и было на живом корпусе: на бессмысленное имя файла справочник
        // предложил «Мастера и Маргариту».
        DIRECTORY.answer("4be534", "Мастер и Маргарита", List.of("Михаил Булгаков"), 1966);
        UUID bookId = announce("4be534", List.of(), null, 2019);

        worker.processDue();

        Awaitility.await().atMost(PATIENCE).until(() -> !"WAITING".equals(status(bookId)));
        assertThat(status(bookId)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("Недоступный справочник откладывает задачу, а не теряет её")
    void postponesWhenDirectoryIsDown() {
        UUID bookId = announce("Learning SQL", List.of("Beaulieu A."), null, 2020);
        DIRECTORY.breakDown();
        try {
            worker.processDue();
        } finally {
            DIRECTORY.repair();
        }

        // Задача осталась в очереди, попытка засчитана, а следующий срок отодвинут в будущее:
        // это и есть «работает при недоступной сети» — книга не теряется и не портится.
        assertThat(status(bookId)).isEqualTo("WAITING");
        assertThat(attempts(bookId)).isEqualTo(1);
        assertThat(nextAttempt(bookId)).isAfter(java.time.OffsetDateTime.now());
    }

    @Test
    @DisplayName("Повторное известие о той же книге второй задачи не заводит")
    void ignoresDuplicateEvent() {
        UUID bookId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send(
                new BookAdded(
                        eventId,
                        bookId,
                        OWNER,
                        "sha",
                        "Java Cookbook",
                        List.of(),
                        null,
                        null,
                        Instant.now()));
        send(
                new BookAdded(
                        eventId,
                        bookId,
                        OWNER,
                        "sha",
                        "Java Cookbook",
                        List.of(),
                        null,
                        null,
                        Instant.now()));

        Awaitility.await().atMost(PATIENCE).until(() -> taskExists(bookId));
        assertThat(taskCount(bookId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Второй раз про ту же книгу справочник не спрашивают")
    void remembersAnswer() {
        DIRECTORY.answer(
                "Java By Comparison Harrer", "Java by Comparison", List.of("Simon Harrer"), 2018);
        UUID first = announce("Java By Comparison", List.of("Harrer S."), null, null);
        worker.processDue();
        awaitEnriched(first);
        int afterFirst = DIRECTORY.calls();

        // Та же книга у другого владельца: вопрос тот же, значит ответ берётся из памяти.
        UUID second = announce("Java By Comparison", List.of("Harrer S."), null, null);
        worker.processDue();
        Awaitility.await().atMost(PATIENCE).until(() -> "DONE".equals(status(second)));

        assertThat(DIRECTORY.calls()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("удаление книги снимает задачу: квота не тратится на то, чего нет")
    void deletedBookIsForgotten() {
        UUID bookId = announce("Книга на удаление", List.of("Автор Г."), null, 2020);
        assertThat(taskExists(bookId)).isTrue();

        deleteBook(bookId);

        Awaitility.await().atMost(PATIENCE).until(() -> !taskExists(bookId));
        // Обложку отпускает тот, кто её положил: у удалённой книги владельца-просителя нет.
        verify(storage, Mockito.timeout(PATIENCE.toMillis())).releaseCover(bookId);
    }

    @Test
    @DisplayName("недоступное хранилище не даёт снять задачу молча: событие вернётся повтором")
    void deletionIsRetriedWhenStorageIsDown() {
        UUID bookId = announce("Книга при недоступном хранилище", List.of("Автор Д."), null, 2020);
        doThrow(new ResourceAccessException("хранилище недоступно"))
                .when(storage)
                .releaseCover(bookId);

        deleteBook(bookId);

        // Проглоченная ошибка означала бы снятую задачу и обложку, которую уже некому убрать.
        // Поэтому обработка откатывается целиком, и задача остаётся до следующей попытки.
        verify(storage, Mockito.timeout(PATIENCE.toMillis()).atLeastOnce()).releaseCover(bookId);
        assertThat(taskExists(bookId)).isTrue();
    }

    private void deleteBook(UUID bookId) {
        kafka.send(
                Topics.BOOK_DELETED,
                bookId.toString(),
                new BookDeleted(UUID.randomUUID(), bookId, OWNER, Instant.now()));
    }

    @Test
    @DisplayName("просьба владельца возвращает в очередь даже закрытую задачу")
    void ownerCanAskAgain() {
        DIRECTORY.answer("Java Cookbook Darwin", "Java Cookbook", List.of("Ian F. Darwin"), 2014);
        UUID bookId = announce("Java Cookbook", List.of("Darwin I."), null, 2014);
        worker.processDue();
        Awaitility.await().atMost(PATIENCE).until(() -> "DONE".equals(status(bookId)));

        kafka.send(
                Topics.BOOK_ENRICHMENT_REQUESTED,
                bookId.toString(),
                new BookEnrichmentRequested(
                        UUID.randomUUID(),
                        bookId,
                        OWNER,
                        "Java Cookbook",
                        List.of("Darwin I."),
                        null,
                        2014,
                        Instant.now()));

        // Закрытая задача не значит исчерпанная: справочник мог с тех пор пополниться.
        Awaitility.await().atMost(PATIENCE).until(() -> "WAITING".equals(status(bookId)));
        assertThat(attempts(bookId)).isZero();
    }

    @Test
    @DisplayName("повторная просьба не сбрасывает уже стоящую в очереди задачу")
    void repeatedAskDoesNotResetQueue() {
        UUID bookId = announce("Книга в очереди", List.of("Автор Е."), null, 2021);
        // Задача уже ждёт: нажатие кнопки не должно позволять ходить к справочнику без конца.
        assertThat(status(bookId)).isEqualTo("WAITING");
        DIRECTORY.breakDown();
        try {
            worker.processDue();
        } finally {
            DIRECTORY.repair();
        }
        assertThat(attempts(bookId)).isEqualTo(1);

        kafka.send(
                Topics.BOOK_ENRICHMENT_REQUESTED,
                bookId.toString(),
                new BookEnrichmentRequested(
                        UUID.randomUUID(),
                        bookId,
                        OWNER,
                        "Книга в очереди",
                        List.of("Автор Е."),
                        null,
                        2021,
                        Instant.now()));

        Awaitility.await()
                .during(java.time.Duration.ofSeconds(3))
                .atMost(PATIENCE)
                .until(() -> attempts(bookId) == 1);
    }

    private UUID announce(String title, List<String> authors, String isbn, Integer year) {
        UUID bookId = UUID.randomUUID();
        send(
                new BookAdded(
                        UUID.randomUUID(),
                        bookId,
                        OWNER,
                        "sha-" + bookId,
                        title,
                        authors,
                        isbn,
                        year,
                        Instant.now()));
        Awaitility.await().atMost(PATIENCE).until(() -> taskExists(bookId));
        return bookId;
    }

    private void send(BookAdded event) {
        kafka.send(Topics.BOOK_ADDED, event.bookId().toString(), event);
    }

    private BookEnriched awaitEnriched(UUID bookId) {
        Awaitility.await().atMost(PATIENCE).until(() -> "DONE".equals(status(bookId)));
        return readLastEnriched(bookId);
    }

    private BookEnriched readLastEnriched(UUID bookId) {
        Map<String, Object> settings =
                KafkaTestUtils.consumerProps(
                        KAFKA.getBootstrapServers(), "test-" + UUID.randomUUID(), true);
        settings.put(
                "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class);
        settings.put(
                "value.deserializer",
                org.springframework.kafka.support.serializer.JacksonJsonDeserializer.class);
        settings.put("spring.json.trusted.packages", "com.bookcase.events");
        settings.put("spring.json.value.default.type", BookEnriched.class.getName());
        settings.put("auto.offset.reset", "earliest");
        try (var consumer =
                new org.apache.kafka.clients.consumer.KafkaConsumer<String, BookEnriched>(
                        settings)) {
            consumer.subscribe(List.of(Topics.BOOK_ENRICHED));
            var records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(15));
            BookEnriched last = null;
            for (var message : records) {
                if (message.value() != null && bookId.equals(message.value().bookId())) {
                    last = message.value();
                }
            }
            assertThat(last).as("находка о книге %s", bookId).isNotNull();
            return last;
        }
    }

    private String status(UUID bookId) {
        return jdbc.sql("SELECT status FROM enrichment_task WHERE book_id = :id")
                .param("id", bookId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private java.time.OffsetDateTime nextAttempt(UUID bookId) {
        return jdbc.sql("SELECT next_attempt_at FROM enrichment_task WHERE book_id = :id")
                .param("id", bookId)
                .query(java.time.OffsetDateTime.class)
                .single();
    }

    private int attempts(UUID bookId) {
        return jdbc.sql(
                        """
                        SELECT coalesce(max(attempts), 0)
                        FROM enrichment_task WHERE book_id = :id
                        """)
                .param("id", bookId)
                .query(Integer.class)
                .single();
    }

    private boolean taskExists(UUID bookId) {
        return taskCount(bookId) > 0;
    }

    private int taskCount(UUID bookId) {
        return jdbc.sql("SELECT count(*) FROM enrichment_task WHERE book_id = :id")
                .param("id", bookId)
                .query(Integer.class)
                .single();
    }
}
