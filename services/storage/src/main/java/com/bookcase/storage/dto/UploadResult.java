package com.bookcase.storage.dto;

/**
 * Итог загрузки.
 *
 * @param sha256 хэш содержимого
 * @param sizeBytes размер
 * @param crc32 контрольная сумма
 * @param contentType тип, заявленный при загрузке
 * @param alreadyStored такие байты в хранилище уже были: второй объект не создан
 */
public record UploadResult(
        String sha256, long sizeBytes, long crc32, String contentType, boolean alreadyStored) {}
