package com.bookcase.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки каталога.
 *
 * @param storageUrl адрес сервиса файлов — за содержимым и пропусками на скачивание
 * @param storagePublicUrl адрес того же сервиса, каким его видит браузер: по ссылке на скачивание
 *     переходит человек, а не сервис
 * @param defaultPageSize сколько книг отдавать, если клиент не попросил иначе
 * @param maxPageSize предел, выше которого запрос не поднимется
 */
@ConfigurationProperties("bookcase.catalog")
public record CatalogProperties(
        String storageUrl,
        String storagePublicUrl,
        @DefaultValue("30") int defaultPageSize,
        @DefaultValue("100") int maxPageSize) {}
