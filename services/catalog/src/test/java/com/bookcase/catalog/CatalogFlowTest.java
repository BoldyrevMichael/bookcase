package com.bookcase.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.events.BookEnriched;
import com.bookcase.events.BookFormat;
import com.bookcase.events.BookMetadata;
import com.bookcase.events.BookMetadataExtracted;
import com.bookcase.events.MetadataSource;
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
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Библиотека целиком: от разобранного файла до поиска, подборок и выгрузки.
 *
 * <p>Поиск проверяется на настоящем PostgreSQL, потому что проверять тут нечего, кроме него: разбор
 * запроса на слова, веса полей, похожесть по триграммам и перечни значений — всё это делает база, и
 * подделка проверяла бы поддельное.
 */
@Testcontainers
@TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CatalogFlowTest {

    private static final String READER = "11111111-1111-1111-1111-111111111111";
    private static final String NEIGHBOUR = "22222222-2222-2222-2222-222222222222";
    private static final Duration PATIENCE = Duration.ofSeconds(30);
    private static final String COVER = "a".repeat(64);
    private static final FakeStorage STORAGE = new FakeStorage();

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    @Container private static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @LocalServerPort private int port;

    @Autowired private KafkaTemplate<Object, Object> kafka;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestIdentity.registerProperties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("bookcase.catalog.storage-url", STORAGE::url);
        registry.add("bookcase.catalog.storage-public-url", STORAGE::url);
    }

    @AfterAll
    static void stopStorage() {
        STORAGE.close();
    }

    @Test
    @DisplayName("01: разобранный файл превращается в карточку")
    void metadataBecomesCard() {
        UUID id =
                addBook(
                        READER,
                        "Погружение в паттерны проектирования",
                        List.of("Швец А."),
                        2018,
                        "ru");

        Map<String, Object> card = fetch(READER, id);
        assertThat(card)
                .containsEntry("title", "Погружение в паттерны проектирования")
                .containsEntry("authors", List.of("Швец А."))
                .containsEntry("status", "READY")
                .containsEntry("shelf", "NONE");
    }

    @Test
    @DisplayName("02: без названия и автора карточка требует просмотра, а не выдумок")
    void incompleteMetadataNeedsReview() {
        UUID id = addBook(READER, null, List.of(), null, null);

        assertThat(fetch(READER, id)).containsEntry("status", "NEEDS_REVIEW");
        assertThat(fetch(READER, id).get("title")).isNull();
    }

    @Test
    @DisplayName("03: поиск находит по слову из названия и по автору")
    void searchFindsByWords() {
        addBook(READER, "Совершенный код", List.of("Макконнелл С."), 2010, "ru");

        assertThat(titles(search(READER, "?q=паттерны")))
                .contains("Погружение в паттерны проектирования");
        assertThat(titles(search(READER, "?q=Макконнелл"))).contains("Совершенный код");
    }

    @Test
    @DisplayName("04: поиск переживает опечатку")
    void searchSurvivesTypo() {
        assertThat(titles(search(READER, "?q=паттрены")))
                .contains("Погружение в паттерны проектирования");
    }

    @Test
    @DisplayName("05: перечни значений считаются по тому же отбору")
    void facetsMatchTheFilter() {
        Map<String, Object> page = search(READER, "");
        Map<String, Object> facets = (Map<String, Object>) page.get("facets");

        List<?> formats = (List<?>) facets.get("formats");
        assertThat(formats).isNotEmpty();
        long epubCount =
                formats.stream()
                        .map(value -> (Map<String, Object>) value)
                        .filter(value -> "EPUB".equals(value.get("value")))
                        .mapToLong(value -> ((Number) value.get("count")).longValue())
                        .sum();
        assertThat(epubCount).isEqualTo(titles(search(READER, "?format=EPUB")).size());
    }

    @Test
    @DisplayName("06: страницы идут одна за другой и книга не показывается дважды")
    void pagesFollowEachOther() {
        Map<String, Object> first = search(READER, "?limit=2");
        List<String> firstIds = ids(first);
        assertThat(firstIds).hasSize(2);

        String cursor = (String) first.get("nextCursor");
        assertThat(cursor).isNotNull();

        assertThat(ids(search(READER, "?limit=2&cursor=" + cursor)))
                .doesNotContainAnyElementsOf(firstIds);
    }

    @Test
    @DisplayName("07: правка помечает поле как исправленное человеком")
    void editMarksFieldAsUserProvided() {
        UUID id = addBook(READER, "Черновое название", List.of("Автор А."), 2000, "ru");

        Map<String, Object> edited =
                client().patch()
                        .uri("/api/v1/books/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(
                                Map.of(
                                        "title",
                                        "Настоящее название",
                                        "themes",
                                        List.of("Java", "Тестирование")))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();

        assertThat(edited)
                .containsEntry("title", "Настоящее название")
                .containsEntry("themes", List.of("Java", "Тестирование"));
        // Год человек не трогал — источник у него остался прежним.
        assertThat(((Map<String, Object>) edited.get("sources")))
                .containsEntry("title", MetadataSource.USER.name())
                .containsEntry("year", MetadataSource.EMBEDDED.name());
    }

    @Test
    @DisplayName("08: отбор по теме показывает только помеченные книги")
    void themeFilterWorks() {
        assertThat(titles(search(READER, "?theme=Java"))).containsExactly("Настоящее название");
    }

    @Test
    @DisplayName("09: полка и избранное живут отдельно от тем")
    void shelfAndFavorite() {
        UUID id = addBook(READER, "Книга для полки", List.of("Кто-то К."), 2021, "ru");

        client().put()
                .uri("/api/v1/books/" + id + "/shelf?value=READING")
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isOk();
        client().put()
                .uri("/api/v1/books/" + id + "/favorite?value=true")
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isOk();

        assertThat(titles(search(READER, "?shelf=READING"))).containsExactly("Книга для полки");
        assertThat(titles(search(READER, "?favorite=true"))).containsExactly("Книга для полки");
    }

    @Test
    @DisplayName("10: подборка собирает книги и отбор по ней работает")
    void collectionsWork() {
        UUID bookId = addBook(READER, "Книга для подборки", List.of("Кто-то К."), 2022, "ru");

        Map<String, Object> collection =
                client().post()
                        .uri("/api/v1/collections")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("name", "к экзамену"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();

        client().put()
                .uri("/api/v1/collections/" + collection.get("id") + "/books/" + bookId)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(titles(search(READER, "?collection=" + collection.get("id"))))
                .containsExactly("Книга для подборки");
    }

    @Test
    @DisplayName("11: ссылка на скачивание собирает имя по схеме")
    void downloadLinkUsesCanonicalName() {
        UUID id =
                addBook(
                        READER,
                        "Совершенный код",
                        List.of("Макконнелл С.", "Кто-то Ещё Е."),
                        2010,
                        "ru");

        Map<String, Object> link =
                client().get()
                        .uri("/api/v1/books/" + id + "/download")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();

        assertThat(link)
                .containsEntry("fileName", "Макконнелл С. и др. - Совершенный код (2010).epub");
        assertThat((String) link.get("url")).contains("/download/");
    }

    @Test
    @DisplayName("12: удаление убирает карточку и отпускает файл")
    void deleteReleasesFile() {
        UUID id = addBook(READER, "Ненужная книга", List.of("Автор А."), 2001, "ru");
        String sha256 = (String) fetch(READER, id).get("sha256");

        client().delete()
                .uri("/api/v1/books/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isNoContent();

        client().get()
                .uri("/api/v1/books/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(STORAGE.releasedFiles()).contains(sha256);
    }

    @Test
    @DisplayName("13: чужая книга не видна ни в поиске, ни по прямой ссылке")
    void foreignBookIsInvisible() {
        UUID id = addBook(READER, "Совсем личная книга", List.of("Автор А."), 2003, "ru");

        assertThat(titles(search(NEIGHBOUR, "?q=личная"))).isEmpty();
        client().get()
                .uri("/api/v1/books/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(NEIGHBOUR))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("14: выгрузка заводит задачу и просит собрать архив")
    void exportCreatesTask() {
        Map<String, Object> task =
                client().post()
                        .uri("/api/v1/exports")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .exchange()
                        .expectStatus()
                        .isAccepted()
                        .expectBody(Map.class)
                        .returnResult()
                        .getResponseBody();

        assertThat(task).containsEntry("status", "QUEUED");
        assertThat((Integer) task.get("bookCount")).isPositive();
    }

    @Test
    @DisplayName("15: уточнение заполняет пустое и помечает поле как пришедшее извне")
    void enrichmentFillsEmptyFields() {
        UUID id = addBook(READER, null, List.of(), null, null);
        assertThat(fetch(READER, id)).containsEntry("status", "NEEDS_REVIEW");

        enrich(id, "The Kubernetes Book", List.of("Nigel Poulton"), 2017, "O'Reilly");
        awaitTitle(id, "The Kubernetes Book");

        Map<String, Object> card = fetch(READER, id);
        // status: карточка перестала требовать просмотра — название и автор теперь есть.
        // hasCover: наружу отдаётся признак, а не хэш — по хэшу картинку можно было бы забрать
        // из хранилища напрямую, минуя проверку владения.
        assertThat(card)
                .containsEntry("authors", List.of("Nigel Poulton"))
                .containsEntry("year", 2017)
                .containsEntry("publisher", "O'Reilly")
                .containsEntry("status", "READY")
                .containsEntry("hasCover", true);
        assertThat(((Map<String, Object>) card.get("sources")))
                .containsEntry("title", MetadataSource.EXTERNAL.name());
        assertThat(card.containsKey("coverSha256")).isFalse();
    }

    @Test
    @DisplayName("18: обложка отдаётся по карточке и только своему владельцу")
    void coverIsServedThroughTheCard() {
        UUID id = addBook(READER, "Книга с обложкой", List.of("Автор А."), 2020, "ru");
        enrich(id, "Книга с обложкой", List.of("Автор А."), 2020, "Издательство");
        awaitPublisher(id);

        byte[] image =
                client().get()
                        .uri("/api/v1/books/" + id + "/cover")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        assertThat(image).isEqualTo(FakeStorage.COVER_IMAGE);

        // Сосед не получит ни обложки, ни подтверждения, что такая книга вообще есть.
        client().get()
                .uri("/api/v1/books/" + id + "/cover")
                .header(HttpHeaders.AUTHORIZATION, bearer(NEIGHBOUR))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("20: удаление книги отпускает и её обложку")
    void deleteReleasesCover() {
        UUID id = addBook(READER, "Книга на удаление", List.of("Автор В."), 2020, "ru");
        enrich(id, "Книга на удаление", List.of("Автор В."), 2020, "Издательство");
        awaitPublisher(id);

        client().delete()
                .uri("/api/v1/books/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isNoContent();

        // Иначе обложка пережила бы книгу и осталась в хранилище навсегда: удалять её
        // по расписанию было бы некому — хранилище про карточки ничего не знает.
        assertThat(STORAGE.releasedCovers()).contains(id.toString());
    }

    @Test
    @DisplayName("19: у книги без обложки её и нет")
    void missingCoverIsNotFound() {
        UUID id = addBook(READER, "Книга без обложки", List.of("Автор Б."), 2020, "ru");

        client().get()
                .uri("/api/v1/books/" + id + "/cover")
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("16: вычитанное из файла уточнение не переписывает")
    void enrichmentKeepsEmbeddedFields() {
        UUID id =
                addBook(
                        READER,
                        "Погружение в паттерны проектирования",
                        List.of("Швец А."),
                        2018,
                        "ru");

        enrich(
                id,
                "Dive Into Design Patterns",
                List.of("Alexander Shvets"),
                2019,
                "Refactoring.Guru");
        awaitPublisher(id);

        Map<String, Object> card = fetch(READER, id);
        // Издательства не было — его дописали, и ниже видно, что оно из справочника.
        assertThat(card)
                .containsEntry("title", "Погружение в паттерны проектирования")
                .containsEntry("authors", List.of("Швец А."))
                .containsEntry("year", 2018)
                .containsEntry("publisher", "Refactoring.Guru");
        assertThat(((Map<String, Object>) card.get("sources")))
                .containsEntry("title", MetadataSource.EMBEDDED.name());
    }

    @Test
    @DisplayName("17: год, угаданный из имени файла, уточнение не перезаписывает")
    void enrichmentDoesNotOverwriteGuessedYear() {
        // Справочники систематически отдают последнее переиздание: на «Java Cookbook» 2014 года
        // приходит 2025-й. У владельца на диске лежит именно то издание, что записано в имени.
        UUID id = addGuessedBook(READER, "Java Cookbook", List.of("Darwin I."), 2014);

        enrich(id, "Java Cookbook", List.of("Ian F. Darwin"), 2025, "O'Reilly");
        awaitPublisher(id);

        Map<String, Object> card = fetch(READER, id);
        assertThat(card).containsEntry("year", 2014);
        assertThat(((Map<String, Object>) card.get("sources")))
                .containsEntry("year", MetadataSource.FILENAME.name());
        // А вот автора, угаданного из имени, справочник уточнил.
        assertThat(card).containsEntry("authors", List.of("Ian F. Darwin"));
        assertThat(((Map<String, Object>) card.get("sources")))
                .containsEntry("authors", MetadataSource.EXTERNAL.name());
    }

    private void enrich(
            UUID bookId, String title, List<String> authors, Integer year, String publisher) {
        kafka.send(
                Topics.BOOK_ENRICHED,
                bookId.toString(),
                new BookEnriched(
                        UUID.randomUUID(),
                        bookId,
                        READER,
                        "тестовый справочник",
                        title,
                        authors,
                        year,
                        null,
                        null,
                        publisher,
                        List.of("Computers"),
                        COVER,
                        Instant.now()));
    }

    private void awaitTitle(UUID id, String expected) {
        Awaitility.await()
                .atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> expected.equals(fetch(READER, id).get("title")));
    }

    private void awaitPublisher(UUID id) {
        Awaitility.await()
                .atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> fetch(READER, id).get("publisher") != null);
    }

    /** Карточка, у которой всё угадано из имени файла: такие и уточняются охотнее всего. */
    private UUID addGuessedBook(String ownerId, String title, List<String> authors, Integer year) {
        String sha256 = UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64);
        Map<String, MetadataSource> sources =
                new java.util.HashMap<>(
                        Map.of(
                                "title", MetadataSource.FILENAME,
                                "authors", MetadataSource.FILENAME,
                                "year", MetadataSource.FILENAME));
        BookMetadata metadata =
                new BookMetadata(
                        BookFormat.PDF,
                        title,
                        authors,
                        year,
                        null,
                        null,
                        null,
                        null,
                        null,
                        sources);
        kafka.send(
                Topics.BOOK_METADATA_EXTRACTED,
                sha256,
                new BookMetadataExtracted(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ownerId,
                        sha256,
                        title + ".pdf",
                        metadata,
                        Instant.now()));
        Awaitility.await()
                .atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> found(ownerId, sha256));
        return UUID.fromString((String) bookByFile(ownerId, sha256).get("id"));
    }

    private UUID addBook(
            String ownerId, String title, List<String> authors, Integer year, String language) {
        String sha256 = UUID.randomUUID().toString().replace("-", "").repeat(2).substring(0, 64);
        BookMetadata metadata =
                new BookMetadata(
                        BookFormat.EPUB,
                        title,
                        authors,
                        year,
                        language,
                        null,
                        null,
                        null,
                        null,
                        sources(title, authors, year, language));
        kafka.send(
                Topics.BOOK_METADATA_EXTRACTED,
                sha256,
                new BookMetadataExtracted(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ownerId,
                        sha256,
                        (title == null ? "без названия" : title) + ".epub",
                        metadata,
                        Instant.now()));

        Awaitility.await()
                .atMost(PATIENCE)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> found(ownerId, sha256));
        return UUID.fromString((String) bookByFile(ownerId, sha256).get("id"));
    }

    private Map<String, MetadataSource> sources(
            String title, List<String> authors, Integer year, String language) {
        Map<String, MetadataSource> sources = new java.util.HashMap<>();
        if (title != null) {
            sources.put("title", MetadataSource.EMBEDDED);
        }
        if (!authors.isEmpty()) {
            sources.put("authors", MetadataSource.EMBEDDED);
        }
        if (year != null) {
            sources.put("year", MetadataSource.EMBEDDED);
        }
        if (language != null) {
            sources.put("language", MetadataSource.EMBEDDED);
        }
        return sources;
    }

    private boolean found(String ownerId, String sha256) {
        return bookByFile(ownerId, sha256) != null;
    }

    private Map<String, Object> bookByFile(String ownerId, String sha256) {
        List<?> items = (List<?>) search(ownerId, "?limit=100").get("items");
        return items.stream()
                .map(item -> (Map<String, Object>) item)
                .filter(item -> sha256.equals(item.get("sha256")))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> fetch(String ownerId, UUID id) {
        return client().get()
                .uri("/api/v1/books/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private Map<String, Object> search(String ownerId, String query) {
        return client().get()
                .uri("/api/v1/books" + query)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private List<String> ids(Map<String, Object> page) {
        return ((List<?>) page.get("items"))
                .stream().map(item -> (String) ((Map<String, Object>) item).get("id")).toList();
    }

    private List<String> titles(Map<String, Object> page) {
        return ((List<?>) page.get("items"))
                .stream()
                        .map(item -> (String) ((Map<String, Object>) item).get("title"))
                        .filter(java.util.Objects::nonNull)
                        .toList();
    }

    private RestTestClient client() {
        return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private static String bearer(String ownerId) {
        return "Bearer " + TestIdentity.tokenFor(ownerId);
    }
}
