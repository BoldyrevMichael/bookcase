package com.bookcase.storage.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Объектное хранилище — единственное место в сервисе, которое знает про S3.
 *
 * <p>Байты сюда и отсюда идут потоком: файл целиком в памяти не оказывается ни при загрузке, ни при
 * выдаче, ни при сборке архива.
 */
@Component
public class ObjectStore {

    private final S3Client client;

    public ObjectStore(S3Client client) {
        this.client = client;
    }

    /** Кладёт объект. Размер известен заранее, поэтому обходимся одним запросом. */
    public void put(String bucket, String key, InputStream content, long size, String contentType) {
        PutObjectRequest request =
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .contentLength(size)
                        .build();
        client.putObject(request, RequestBody.fromInputStream(content, size));
    }

    /** Кладёт объект из файла: так загружается собранный архив. */
    public void putFile(String bucket, String key, Path file, String contentType) {
        PutObjectRequest request =
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
        client.putObject(request, RequestBody.fromFile(file));
    }

    /** Открывает объект на чтение. Поток закрывает вызывающий. */
    public InputStream open(String bucket, String key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /** Переливает объект в указанный приёмник. */
    public void copyTo(String bucket, String key, OutputStream target) throws java.io.IOException {
        try (InputStream source = open(bucket, key)) {
            source.transferTo(target);
        }
    }

    /** Размер объекта, если он есть. Нужен заголовку ответа и проверке, что архив собран. */
    public java.util.Optional<Long> sizeOf(String bucket, String key) {
        try {
            return java.util.Optional.of(
                    client.headObject(
                                    software.amazon.awssdk.services.s3.model.HeadObjectRequest
                                            .builder()
                                            .bucket(bucket)
                                            .key(key)
                                            .build())
                            .contentLength());
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException _) {
            return java.util.Optional.empty();
        }
    }

    public void delete(String bucket, String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
