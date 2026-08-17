package com.bookcase.catalog.dto;

import java.time.Instant;

/**
 * Ссылка на скачивание.
 *
 * @param url адрес, по которому файл отдаётся без заголовка с токеном
 * @param fileName имя, под которым файл сохранится
 * @param expiresAt до какого момента ссылка действует
 */
public record DownloadLink(String url, String fileName, Instant expiresAt) {}
