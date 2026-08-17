package com.bookcase.storage.dto;

/**
 * Собранный архив в хранилище.
 *
 * @param key имя объекта в корзине экспортов
 * @param sizeBytes размер архива
 * @param fileCount сколько файлов в него попало
 */
public record ArchiveLocation(String key, long sizeBytes, int fileCount) {}
