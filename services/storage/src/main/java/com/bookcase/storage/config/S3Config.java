package com.bookcase.storage.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Клиент объектного хранилища. */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(StorageProperties properties) {
        StorageProperties.S3 s3 = properties.s3();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.endpoint()))
                .region(Region.of(s3.region()))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                // Имя корзины в пути, а не в имени узла: у своего хранилища нет
                // сертификата на *.домен, да и адрес обычно задан по IP.
                .forcePathStyle(true)
                // Начиная с версии 2.30 клиент по умолчанию досылает контрольные суммы
                // отдельным блоком в конце тела запроса. Хранилища, совместимые с S3,
                // такое тело часто не разбирают и отвечают «подпись не сходится».
                // Просим считать суммы только там, где протокол этого требует.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
