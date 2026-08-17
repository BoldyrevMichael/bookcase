package com.bookcase.storage.dto;

/**
 * Хранимый файл вместе с именем, под которым его положил обратившийся владелец.
 *
 * @param sha256 хэш содержимого, он же имя объекта в хранилище
 * @param sizeBytes размер
 * @param crc32 контрольная сумма, нужная заголовку записи в архиве
 * @param contentType тип, заявленный при загрузке
 * @param originalName имя файла у этого владельца
 */
public record StoredFile(
        String sha256, long sizeBytes, long crc32, String contentType, String originalName) {}
