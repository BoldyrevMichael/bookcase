package com.bookcase.ingester.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Настройки разбора.
 *
 * @param storageUrl адрес сервиса файлов
 * @param maxFileSize предел размера файла, который вообще имеет смысл разбирать
 * @param retryAttempts сколько всего попыток разбора делать при временной неудаче
 * @param retryDelayMillis пауза между попытками в миллисекундах: столько же читает аннотация
 *     повторов, а она умеет только число
 */
@ConfigurationProperties("bookcase.ingester")
public record IngesterProperties(
        String storageUrl,
        @DefaultValue("1GB") DataSize maxFileSize,
        @DefaultValue("4") int retryAttempts,
        @DefaultValue("30000") long retryDelayMillis) {}
