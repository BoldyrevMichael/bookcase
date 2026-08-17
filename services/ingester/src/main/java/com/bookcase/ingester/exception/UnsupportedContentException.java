package com.bookcase.ingester.exception;

/**
 * Файл разобрать нельзя, и повторять попытку бессмысленно: это не книга, книга испорчена или формат
 * не поддерживается.
 *
 * <p>Отделяется от временных неудач намеренно. Временную — недоступное хранилище, оборванное
 * соединение — нужно повторить; такую повторять незачем, и задача сразу получает отказ с причиной,
 * которую можно показать человеку.
 */
public class UnsupportedContentException extends RuntimeException {

    public UnsupportedContentException(String message) {
        super(message);
    }

    public UnsupportedContentException(String message, Throwable cause) {
        super(message, cause);
    }
}
