package com.bookcase.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.bookcase.storage.config.StorageProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * Приём, выдача и сборка архива — на настоящих База и хранилище.
 *
 * <p>Подделки здесь были бы бессмысленны: проверяется как раз то, что делают эти двое — что
 * одинаковые байты не превращаются в два объекта, что чужой файл не виден, и что архив собирается
 * без пережатия.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorageFlowTest {

    /** Тело ответа как разобранный JSON: без параметра типа компилятор ругается на приведение. */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    private static final String READER = "11111111-1111-1111-1111-111111111111";
    private static final String NEIGHBOUR = "22222222-2222-2222-2222-222222222222";
    private static final byte[] BOOK =
            "Содержимое книги, которое повторяется".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ANOTHER_BOOK =
            "Совсем другая книга".getBytes(StandardCharsets.UTF_8);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine");

    @Container
    private static final GenericContainer<?> SEAWEEDFS =
            new GenericContainer<>("chrislusf/seaweedfs:4.41")
                    .withCopyToContainer(
                            MountableFile.forClasspathResource("seaweedfs-s3.json"), "/etc/s3.json")
                    .withCommand(
                            "server",
                            "-dir=/data",
                            "-s3",
                            "-s3.config=/etc/s3.json",
                            "-ip.bind=0.0.0.0")
                    .withExposedPorts(8333, 8888, 9333)
                    // Ждать один только мастер недостаточно: корзины заводятся через файлер,
                    // и пока он не поднялся, команда отвечает «нет адреса файлера».
                    .waitingFor(
                            new WaitAllStrategy()
                                    .withStrategy(Wait.forHttp("/cluster/healthz").forPort(9333))
                                    .withStrategy(
                                            Wait.forHttp("/")
                                                    .forPort(8888)
                                                    .forStatusCodeMatching(code -> code < 500))
                                    .withStartupTimeout(Duration.ofMinutes(2)));

    @LocalServerPort private int port;

    @Autowired private S3Client s3;

    @Autowired private JdbcClient jdbc;

    @Autowired private StorageProperties properties;

    private RestTestClient client;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        TestIdentity.registerProperties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "bookcase.storage.s3.endpoint",
                () -> "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333));
    }

    /**
     * Корзины заводятся заранее, как и на стенде: у сервиса нет права их создавать. Ответ команды
     * проверяется — молча не заведённая корзина превратила бы отказ хранилища в загадку.
     */
    @org.junit.jupiter.api.BeforeAll
    static void createBuckets() throws Exception {
        org.testcontainers.containers.Container.ExecResult result =
                SEAWEEDFS.execInContainer(
                        "sh",
                        "-c",
                        """
                        echo 's3.bucket.create -name books
                        s3.bucket.create -name exports
                        s3.bucket.create -name covers' | weed shell -master=localhost:9333""");
        assertThat(result.getStdout() + result.getStderr())
                .as("создание корзин в SeaweedFS")
                .contains("created bucket books")
                .contains("created bucket exports")
                .contains("created bucket covers");
    }

    @org.junit.jupiter.api.BeforeEach
    void prepare() {
        client = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        jdbc.sql("DELETE FROM file_reference").update();
        jdbc.sql("DELETE FROM stored_file").update();
        objectKeys(properties.booksBucket())
                .forEach(
                        key ->
                                s3.deleteObject(
                                        builder ->
                                                builder.bucket(properties.booksBucket()).key(key)));
    }

    @Test
    @DisplayName("повторная загрузка того же файла не создаёт второй объект")
    void sameContentIsStoredOnce() {
        Map<String, Object> first = upload(READER, "книга.epub", BOOK);
        Map<String, Object> second = upload(READER, "она же под другим именем.epub", BOOK);

        assertThat(second).containsEntry("sha256", first.get("sha256"));
        assertThat(first).containsEntry("alreadyStored", false);
        assertThat(second).containsEntry("alreadyStored", true);
        assertThat(objectKeys(properties.booksBucket()))
                .containsExactly((String) first.get("sha256"));
        assertThat(referenceCount((String) first.get("sha256"))).isEqualTo(1);
    }

    @Test
    @DisplayName("те же байты у другого владельца: объект по-прежнему один, ссылок две")
    void secondOwnerSharesTheSameObject() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");
        upload(NEIGHBOUR, "книга.epub", BOOK);

        assertThat(objectKeys(properties.booksBucket())).hasSize(1);
        assertThat(referenceCount(sha256)).isEqualTo(2);
    }

    @Test
    @DisplayName("выдача возвращает те же байты и имя, под которым файл положили")
    void downloadReturnsStoredBytes() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");

        client.get()
                .uri("/api/v1/files/" + sha256)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueMatches(HttpHeaders.CONTENT_DISPOSITION, ".*attachment.*")
                .expectBody(byte[].class)
                .value(body -> assertThat(body).isEqualTo(BOOK));
    }

    @Test
    @DisplayName("чужой файл не существует: 404, а не отказ в доступе")
    void foreignFileIsNotFound() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");

        client.get()
                .uri("/api/v1/files/" + sha256)
                .header(HttpHeaders.AUTHORIZATION, bearer(NEIGHBOUR))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("объект удаляется, только когда ушёл последний владелец")
    void objectSurvivesWhileSomeoneHoldsIt() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");
        upload(NEIGHBOUR, "книга.epub", BOOK);

        release(READER, sha256);
        assertThat(objectKeys(properties.booksBucket())).hasSize(1);
        assertThat(referenceCount(sha256)).isEqualTo(1);

        release(NEIGHBOUR, sha256);
        assertThat(objectKeys(properties.booksBucket())).isEmpty();
        assertThat(referenceCount(sha256)).isZero();
    }

    @Test
    @DisplayName("по подписанному пропуску файл отдаётся без токена, по чужому — нет")
    void ticketReplacesToken() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");

        String ticket =
                (String)
                        client.post()
                                .uri("/api/v1/files/" + sha256 + "/ticket")
                                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                                .exchange()
                                .expectStatus()
                                .isOk()
                                .expectBody(JSON_OBJECT)
                                .returnResult()
                                .getResponseBody()
                                .get("token");

        client.get()
                .uri("/api/v1/files/" + sha256 + "/download/" + ticket)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(byte[].class)
                .value(body -> assertThat(body).isEqualTo(BOOK));

        client.get()
                .uri("/api/v1/files/" + sha256 + "/download/" + ticket + "x")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("обложка кладётся по роли и читается вошедшим")
    void coverIsStoredAndServedToSignedIn() {
        byte[] picture = "не настоящая картинка, но её байты".getBytes(StandardCharsets.UTF_8);

        Map<String, Object> stored =
                client.post()
                        .uri("/api/v1/covers?holder=" + UUID.randomUUID() + "&owner=" + READER)
                        .header(HttpHeaders.AUTHORIZATION, coverWriter())
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(picture)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JSON_OBJECT)
                        .returnResult()
                        .getResponseBody();

        String sha256 = (String) stored.get("sha256");
        assertThat(sha256).hasSize(64);

        byte[] served =
                client.get()
                        .uri("/api/v1/covers/" + sha256)
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();
        assertThat(served).isEqualTo(picture);
    }

    @Test
    @DisplayName("без входа обложку не прочитать: иначе по ней проверяют чужую библиотеку")
    void coverIsNotPublic() {
        // Хэш обложки может посчитать кто угодно, скачав ту же картинку у справочника.
        // Открытый адрес отвечал бы «есть» или «нет» на вопрос, лежит ли книга в библиотеке,
        // — а на чужую книгу сервис отвечает 404 именно для того, чтобы этого не сообщать.
        String sha256 =
                (String)
                        client.post()
                                .uri(
                                        "/api/v1/covers?holder="
                                                + UUID.randomUUID()
                                                + "&owner="
                                                + READER)
                                .header(HttpHeaders.AUTHORIZATION, coverWriter())
                                .contentType(MediaType.IMAGE_JPEG)
                                .body("обложка книги".getBytes(StandardCharsets.UTF_8))
                                .exchange()
                                .expectStatus()
                                .isOk()
                                .expectBody(JSON_OBJECT)
                                .returnResult()
                                .getResponseBody()
                                .get("sha256");

        client.get().uri("/api/v1/covers/" + sha256).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("без роли обложку положить нельзя, даже с действительным токеном")
    void coverUploadRequiresRole() {
        client.post()
                .uri("/api/v1/covers?holder=" + UUID.randomUUID() + "&owner=" + READER)
                .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                .contentType(MediaType.IMAGE_JPEG)
                .body("картинка".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    @DisplayName("под видом обложки не принимается что попало")
    void coverMustBeAnImage() {
        client.post()
                .uri("/api/v1/covers?holder=" + UUID.randomUUID() + "&owner=" + READER)
                .header(HttpHeaders.AUTHORIZATION, coverWriter())
                .contentType(MediaType.APPLICATION_PDF)
                .body("%PDF-1.7 это книга, а не обложка".getBytes(StandardCharsets.UTF_8))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @DisplayName("обложка живёт, пока её показывает хоть одна карточка")
    void coverOutlivesOnlyItsHolders() {
        byte[] picture = "обложка на две карточки".getBytes(StandardCharsets.UTF_8);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        String sha256 = putCover(picture, first, READER);
        assertThat(putCover(picture, second, READER)).isEqualTo(sha256);
        assertThat(objectKeys(properties.coversBucket())).contains(sha256);

        // Первая карточка удалена — обложка остаётся: её показывает вторая.
        releaseCover(first, READER);
        assertThat(objectKeys(properties.coversBucket())).contains(sha256);

        // Ушла и вторая — показывать некому, картинка уходит вместе с ней.
        releaseCover(second, READER);
        assertThat(objectKeys(properties.coversBucket())).doesNotContain(sha256);
        assertThat(coverCount(sha256)).isZero();
    }

    @Test
    @DisplayName("чужую обложку отпустить нельзя")
    void foreignCoverCannotBeReleased() {
        UUID holder = UUID.randomUUID();
        String sha256 = putCover("чужая обложка".getBytes(StandardCharsets.UTF_8), holder, READER);

        releaseCover(holder, NEIGHBOUR);

        assertThat(objectKeys(properties.coversBucket())).contains(sha256);
    }

    @Test
    @DisplayName("тот, кто кладёт обложки, может убрать их за исчезнувшей карточкой")
    void coverWriterCanReleaseOrphan() {
        // Так бывает, когда книгу удалили, пока справочник думал: обложка приехала к
        // несуществующей карточке, и владелец её уже не отпустит — просить некому.
        UUID holder = UUID.randomUUID();
        String sha256 = putCover("обложка-сирота".getBytes(StandardCharsets.UTF_8), holder, READER);

        client.delete()
                .uri("/api/v1/covers/holders/" + holder)
                .header(HttpHeaders.AUTHORIZATION, coverWriter())
                .exchange()
                .expectStatus()
                .isNoContent();

        assertThat(objectKeys(properties.coversBucket())).doesNotContain(sha256);
    }

    @Test
    @DisplayName("архив собирается без пережатия и содержит выбранные файлы")
    void archiveIsAssembledWithoutCompression() throws Exception {
        String first = (String) upload(READER, "первая.epub", BOOK).get("sha256");
        String second = (String) upload(READER, "вторая.pdf", ANOTHER_BOOK).get("sha256");

        byte[] archive =
                client.post()
                        .uri("/api/v1/archives/download")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("files", List.of(first, second)))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(byte[].class)
                        .returnResult()
                        .getResponseBody();

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            assertThat(entry.getName()).isEqualTo("первая.epub");
            assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(zip.readAllBytes()).isEqualTo(BOOK);

            entry = zip.getNextEntry();
            assertThat(entry.getName()).isEqualTo("вторая.pdf");
            assertThat(entry.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(zip.readAllBytes()).isEqualTo(ANOTHER_BOOK);

            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    @DisplayName("тот же архив можно собрать в хранилище")
    void archiveCanBeStoredInBucket() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");

        Map<String, Object> location =
                client.post()
                        .uri("/api/v1/archives")
                        .header(HttpHeaders.AUTHORIZATION, bearer(READER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("files", List.of(sha256)))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JSON_OBJECT)
                        .returnResult()
                        .getResponseBody();

        assertThat((String) location.get("key")).startsWith(READER + "/").endsWith(".zip");
        assertThat(objectKeys(properties.exportsBucket())).contains((String) location.get("key"));
    }

    @Test
    @DisplayName("чужой файл нельзя добавить в архив: отказ, а не молчаливый пропуск")
    void archiveRejectsForeignFile() {
        String sha256 = (String) upload(READER, "книга.epub", BOOK).get("sha256");

        client.post()
                .uri("/api/v1/archives/download")
                .header(HttpHeaders.AUTHORIZATION, bearer(NEIGHBOUR))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("files", List.of(sha256)))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    @DisplayName("без токена не принимают и не отдают")
    void tokenIsRequired() {
        client.get()
                .uri("/api/v1/files/" + "0".repeat(64))
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    private Map<String, Object> upload(String ownerId, String name, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(
                "file",
                new ByteArrayResource(content) {
                    @Override
                    public String getFilename() {
                        return name;
                    }
                });

        @SuppressWarnings("unchecked")
        Map<String, Object> result =
                client.post()
                        .uri("/api/v1/files")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(JSON_OBJECT)
                        .returnResult()
                        .getResponseBody();
        return result;
    }

    private void release(String ownerId, String sha256) {
        client.delete()
                .uri("/api/v1/files/" + sha256)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private List<String> objectKeys(String bucket) {
        return s3
                .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()
                .stream()
                .map(software.amazon.awssdk.services.s3.model.S3Object::key)
                .toList();
    }

    private int referenceCount(String sha256) {
        return jdbc.sql("SELECT count(*) FROM file_reference WHERE sha256 = :sha256")
                .param("sha256", sha256)
                .query(Integer.class)
                .single();
    }

    private static String bearer(String ownerId) {
        return "Bearer " + TestIdentity.tokenFor(ownerId);
    }

    private String putCover(byte[] content, UUID holder, String owner) {
        return (String)
                client.post()
                        .uri("/api/v1/covers?holder=" + holder + "&owner=" + owner)
                        .header(HttpHeaders.AUTHORIZATION, coverWriter())
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(content)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JSON_OBJECT)
                        .returnResult()
                        .getResponseBody()
                        .get("sha256");
    }

    private void releaseCover(UUID holder, String ownerId) {
        client.delete()
                .uri("/api/v1/covers/holders/" + holder)
                .header(HttpHeaders.AUTHORIZATION, bearer(ownerId))
                .exchange()
                .expectStatus()
                .isNoContent();
    }

    private int coverCount(String sha256) {
        return jdbc.sql("SELECT count(*) FROM cover WHERE sha256 = :sha256")
                .param("sha256", sha256)
                .query(Integer.class)
                .single();
    }

    /** Токен служебной учётной записи уточнителя: единственная роль — право класть обложки. */
    private static String coverWriter() {
        return "Bearer " + TestIdentity.tokenFor("service-account", List.of("covers-writer"));
    }
}
