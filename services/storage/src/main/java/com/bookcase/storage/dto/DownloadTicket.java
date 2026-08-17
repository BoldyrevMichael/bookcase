package com.bookcase.storage.dto;

import java.time.Instant;

/**
 * Подписанная ссылка на скачивание.
 *
 * @param token сам пропуск, передаётся параметром запроса
 * @param expiresAt до какого момента он действует
 */
public record DownloadTicket(String token, Instant expiresAt) {}
