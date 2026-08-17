package com.bookcase.storage.exception;

/**
 * Файла нет — или он есть, но принадлежит не обратившемуся.
 *
 * <p>Эти два случая намеренно неразличимы: иначе по ответу можно было бы узнать, что такой файл в
 * хранилище существует.
 */
public class StoredFileNotFoundException extends RuntimeException {

    public StoredFileNotFoundException(String sha256) {
        super("файл " + sha256 + " не найден");
    }
}
